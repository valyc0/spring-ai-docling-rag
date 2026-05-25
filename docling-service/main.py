"""
Docling parsing microservice.

Espone una REST API per il parsing di documenti (PDF, DOCX, HTML, PPTX, …).
Usa Docling (IBM) per estrarre il documento e restituirlo nel formato nativo JSON
oppure in Markdown.

Endpoints:
  POST /parse         - upload file → JSON nativo Docling (o ?md per Markdown)
                        ?ocr    abilita OCR (default: disabilitato)
                        ?tables abilita rilevamento struttura tabelle (default: disabilitato)
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

_MAX_CONCURRENT = int(os.environ.get("DOCLING_MAX_CONCURRENT", 1))
_NUM_THREADS    = int(os.environ.get("DOCLING_THREADS", max(2, (os.cpu_count() or 4) // _MAX_CONCURRENT)))
_TIMEOUT_SEC    = int(os.environ.get("DOCLING_TIMEOUT_SEC", 300))
_MAX_FILE_MB    = int(os.environ.get("DOCLING_MAX_FILE_MB", 100))
_DEVICE_STR     = os.environ.get("DOCLING_DEVICE", "cpu").lower()

logger.info(
    "Docling config: threads=%d, max_concurrent=%d, timeout=%ds, max_file=%dMB, device=%s",
    _NUM_THREADS, _MAX_CONCURRENT, _TIMEOUT_SEC, _MAX_FILE_MB, _DEVICE_STR,
)


# ── Semaforo: limita le conversioni concorrenti ───────────────────────────────
_semaphore: asyncio.Semaphore  # inizializzato in startup


def _make_converter(do_ocr: bool = False, do_table_structure: bool = False) -> DocumentConverter:
    """Crea un converter Docling con le opzioni richieste."""
    opts = PdfPipelineOptions()
    opts.do_ocr = do_ocr
    opts.do_table_structure = do_table_structure
    opts.generate_page_images = False
    opts.generate_picture_images = False
    opts.accelerator_options = AcceleratorOptions(
        num_threads=_NUM_THREADS,
        device=_DEVICE_STR,
    )
    return DocumentConverter(
        format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)}
    )


# ── Worker process pool ───────────────────────────────────────────────────────
_worker_pool: ProcessPoolExecutor
# Cache converter per combinazione (ocr, tables) — uno per processo worker
_worker_converters: dict = {}


def _worker_init():
    """Pre-carica il converter di default (ocr=False, tables=False) al primo avvio."""
    _worker_converters[(False, False)] = _make_converter(False, False)
    logger.info("Worker PID=%d: converter inizializzato", os.getpid())


def _convert_in_worker(tmp_path: str, do_ocr: bool, do_table_structure: bool, max_pages: int) -> dict:
    """
    Eseguito nel processo worker.
    Riusa il converter per la combinazione (ocr, tables); lo crea se non esiste ancora.
    """
    key = (do_ocr, do_table_structure)
    if key not in _worker_converters:
        _worker_converters[key] = _make_converter(do_ocr, do_table_structure)
        logger.info("Worker PID=%d: nuovo converter per ocr=%s tables=%s", os.getpid(), do_ocr, do_table_structure)
    kwargs = {}
    if max_pages > 0:
        kwargs["page_range"] = (1, max_pages)
    result = _worker_converters[key].convert(tmp_path, **kwargs)
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
    md: Optional[str] = Query(None, description="Se presente, ritorna il documento in formato Markdown"),
    ocr: Optional[str] = Query(None, description="Se presente, abilita OCR"),
    tables: Optional[str] = Query(None, description="Se presente, abilita rilevamento struttura tabelle"),
    pages: Optional[int] = Query(None, ge=1, description="Numero massimo di pagine da convertire"),
):
    """
    Converte un documento con Docling.
    - Default: restituisce il JSON nativo di Docling (identico a `docling convert <file> --to json`).
    - ?md        : restituisce il testo Markdown.
    - ?ocr       : abilita OCR (più lento, utile per PDF scansionati).
    - ?tables    : abilita rilevamento struttura tabelle.
    - ?pages=N   : limita la conversione alle prime N pagine.
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

    do_ocr = ocr is not None
    do_tables = tables is not None
    max_pages = pages if pages is not None else 0
    logger.info(
        "Parsing documento: %s (%.1f MB) ocr=%s tables=%s pages=%s",
        file.filename, len(content) / (1024 * 1024), do_ocr, do_tables,
        max_pages if max_pages > 0 else "all",
    )

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
                        functools.partial(_convert_in_worker, tmp_path, do_ocr, do_tables, max_pages),
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
