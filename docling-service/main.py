"""
Docling parsing microservice.

Espone una REST API per il parsing di documenti (PDF, DOCX, HTML, PPTX, …).
Usa Docling (IBM) per estrarre il documento e restituirlo nel formato nativo JSON
oppure in Markdown.

Endpoints:
  POST /parse         - upload file → JSON nativo Docling (o ?md per Markdown)
  GET  /health        - health check

Configurazione via variabili d'ambiente:
  DOCLING_THREADS        numero di thread CPU per modello (default: tutti i core)
  DOCLING_MAX_CONCURRENT numero massimo di conversioni simultanee (default: 2)
  DOCLING_TIMEOUT_SEC    timeout per singola conversione in secondi (default: 300)
  DOCLING_MAX_FILE_MB    dimensione massima file in MB (default: 100)
  DOCLING_DEVICE         dispositivo acceleratore: CPU | CUDA | MPS | AUTO (default: AUTO)
"""

import asyncio
import logging
import tempfile
import os
import functools
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, File, Query, UploadFile, HTTPException
from fastapi.responses import JSONResponse, Response

from docling.document_converter import DocumentConverter, PdfFormatOption
from docling.datamodel.base_models import InputFormat
from docling.datamodel.pipeline_options import PdfPipelineOptions, AcceleratorOptions

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ── Configurazione da environment ─────────────────────────────────────────────

_NUM_THREADS    = int(os.environ.get("DOCLING_THREADS", os.cpu_count() or 4))
_MAX_CONCURRENT = int(os.environ.get("DOCLING_MAX_CONCURRENT", 2))
_TIMEOUT_SEC    = int(os.environ.get("DOCLING_TIMEOUT_SEC", 300))
_MAX_FILE_MB    = int(os.environ.get("DOCLING_MAX_FILE_MB", 100))
_DEVICE_STR     = os.environ.get("DOCLING_DEVICE", "AUTO").upper()

_DEVICE_MAP = {
    "CPU":  "cpu",
    "CUDA": "cuda",
    "MPS":  "mps",
}
_DEVICE = _DEVICE_MAP.get(_DEVICE_STR, "cpu")

logger.info(
    "Docling config: threads=%d, max_concurrent=%d, timeout=%ds, max_file=%dMB, device=%s",
    _NUM_THREADS, _MAX_CONCURRENT, _TIMEOUT_SEC, _MAX_FILE_MB, _DEVICE_STR,
)


# ── Semaforo: limita le conversioni concorrenti ───────────────────────────────
_semaphore: asyncio.Semaphore  # inizializzato in startup


def _make_converter() -> DocumentConverter:
    """Crea un converter Docling isolato (uno per processo worker)."""
    opts = PdfPipelineOptions()
    opts.do_ocr = False
    opts.do_table_structure = True
    opts.generate_page_images = False
    opts.generate_picture_images = False
    opts.accelerator_options = AcceleratorOptions(
        num_threads=_NUM_THREADS,
        device=_DEVICE,
    )
    return DocumentConverter(
        format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)}
    )


# ── Worker process pool ───────────────────────────────────────────────────────
_worker_pool: ProcessPoolExecutor
_worker_converter: Optional[DocumentConverter] = None  # solo nei processi worker


def _worker_init():
    """Inizializzazione del processo worker: carica i modelli una sola volta."""
    global _worker_converter
    _worker_converter = _make_converter()
    logger.info("Worker PID=%d: converter inizializzato", os.getpid())


def _convert_in_worker(tmp_path: str) -> dict:
    """
    Eseguito nel processo worker.
    Restituisce il JSON nativo di Docling e il Markdown del documento.
    """
    result = _worker_converter.convert(tmp_path)
    doc = result.document
    return {
        "docling_json": doc.export_to_dict(),
        "markdown": doc.export_to_markdown(),
    }


app = FastAPI(title="Docling Parsing Service", version="1.0.0")


@app.on_event("startup")
async def startup():
    global _semaphore, _worker_pool
    _semaphore = asyncio.Semaphore(_MAX_CONCURRENT)
    _worker_pool = ProcessPoolExecutor(
        max_workers=_MAX_CONCURRENT,
        initializer=_worker_init,
    )
    logger.info("ProcessPoolExecutor avviato con %d worker(s)", _MAX_CONCURRENT)


@app.on_event("shutdown")
async def shutdown():
    _worker_pool.shutdown(wait=False)
    logger.info("ProcessPoolExecutor fermato")

# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "UP", "service": "docling-service"}


@app.post("/parse")
async def parse_document(
    file: UploadFile = File(...),
    md: Optional[bool] = Query(None, description="Se presente, ritorna il documento in formato Markdown"),
):
    """
    Converte un documento con Docling.
    - Default: restituisce il JSON nativo di Docling (identico a `docling convert <file> --to json`).
    - ?md : restituisce il testo Markdown (identico a `docling convert <file> --to md`).
    Accetta: PDF, DOCX, HTML, PPTX, XLSX, Markdown, AsciiDoc.
    """
    if not file.filename:
        raise HTTPException(status_code=400, detail="Nome file mancante")

    suffix = Path(file.filename).suffix.lower()
    allowed = {".pdf", ".docx", ".doc", ".html", ".htm", ".pptx", ".xlsx", ".md", ".adoc"}
    if suffix not in allowed:
        raise HTTPException(
            status_code=415,
            detail=f"Formato non supportato: {suffix}. Supportati: {allowed}",
        )

    content = await file.read()

    max_bytes = _MAX_FILE_MB * 1024 * 1024
    if len(content) > max_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"File troppo grande: {len(content) // (1024*1024)}MB (max {_MAX_FILE_MB}MB)",
        )

    logger.info("Parsing documento: %s (%.1f MB)", file.filename, len(content) / (1024 * 1024))

    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(content)
        tmp_path = tmp.name

    try:
        async with _semaphore:
            loop = asyncio.get_running_loop()
            try:
                raw = await asyncio.wait_for(
                    loop.run_in_executor(
                        _worker_pool,
                        functools.partial(_convert_in_worker, tmp_path),
                    ),
                    timeout=_TIMEOUT_SEC,
                )
            except asyncio.TimeoutError:
                raise HTTPException(
                    status_code=504,
                    detail=f"Timeout: conversione superato {_TIMEOUT_SEC}s",
                )

        logger.info("Parsing completato: %s", file.filename)

        if md is not None:
            return Response(content=raw["markdown"], media_type="text/markdown")

        return JSONResponse(content=raw["docling_json"])

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Errore nel parsing di %s", file.filename)
        raise HTTPException(status_code=500, detail=f"Errore parsing: {str(e)}")
    finally:
        os.unlink(tmp_path)
