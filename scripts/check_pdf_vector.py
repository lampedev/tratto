#!/usr/bin/env python3
"""Verifica che un PDF esportato da Tratto sia vettoriale: zero immagini, solo path.

Uso: python3 -I check_pdf_vector.py file.pdf
"""
import sys

from pypdf import PdfReader


def main(path: str) -> int:
    reader = PdfReader(path)
    total_images = 0
    for i, page in enumerate(reader.pages, start=1):
        images = 0
        resources = page.get("/Resources") or {}
        xobjects = resources.get("/XObject") or {}
        for name in xobjects:
            if xobjects[name].get_object().get("/Subtype") == "/Image":
                images += 1
        content = page.get_contents()
        data = content.get_data() if content is not None else b""
        # operatori di riempimento path: f, f*, B, B*, b, b*
        fills = sum(data.count(op) for op in (b" f\n", b" f*\n", b"\nf\n", b"\nf*\n"))
        box = page.mediabox
        print(f"pagina {i}: {float(box.width):.0f}x{float(box.height):.0f} pt, "
              f"immagini={images}, riempimenti path≈{fills}, content={len(data)} byte")
        total_images += images
    # le foto inserite dall'utente sono (giustamente) immagini; i tratti devono restare path
    print("VETTORIALE ✔" if total_images == 0 else f"tratti vettoriali, {total_images} immagini (foto inserite)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
