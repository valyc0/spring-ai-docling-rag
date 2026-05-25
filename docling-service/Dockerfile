FROM python:3.11-slim

WORKDIR /app

# Dipendenze sistema per Docling (OCR opzionale, rendering PDF)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download dei modelli Docling al build time (evita cold start)
# Usa la stessa configurazione di _make_converter() in main.py
RUN python -c "\
from docling.document_converter import DocumentConverter, PdfFormatOption; \
from docling.datamodel.base_models import InputFormat; \
from docling.datamodel.pipeline_options import PdfPipelineOptions, AcceleratorOptions; \
opts = PdfPipelineOptions(); \
opts.do_ocr = False; \
opts.do_table_structure = False; \
opts.generate_page_images = False; \
opts.generate_picture_images = False; \
opts.accelerator_options = AcceleratorOptions(num_threads=4, device='cpu'); \
DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)})"
COPY main.py .

EXPOSE 8001

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
