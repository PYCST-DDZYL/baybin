"""Replace a text report only after its new contents have been written completely."""
import os
from pathlib import Path
import tempfile


def write_text(path, text):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", dir=target.parent,
                                         prefix=target.name + ".", suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            output.write(text)
        os.replace(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
