#!/usr/bin/env python3
"""NUR LOKAL: erzeugt eine kleine PDF mit Textebene (ohne Abhängigkeiten) – für dev-files.sh."""
import sys

lines = sys.argv[2:] or ["Beispiel"]
text = "BT /F1 12 Tf 72 720 Td 16 TL " + " ".join(f"({l.replace('(', '').replace(')', '')}) Tj T*" for l in lines) + " ET"
objs = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
    f"<< /Length {len(text)} >>\nstream\n{text}\nendstream",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
]
out, offsets = b"%PDF-1.4\n", []
for i, o in enumerate(objs, 1):
    offsets.append(len(out))
    out += f"{i} 0 obj\n{o}\nendobj\n".encode("latin-1")
xref = len(out)
out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode() + b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
open(sys.argv[1], "wb").write(out)
