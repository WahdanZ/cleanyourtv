"""Sets the admin panel's password (asked twice, never shown or saved in plain text), then restarts the helper.

    python helper/set_admin_password.py
Signs out every device that was signed in to the admin panel."""
import getpass
import json
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from admin import hash_password  # noqa: E402

DATA = Path(__file__).resolve().parent.parent / "data"

while True:
    first = getpass.getpass("New admin password (at least 8 characters, not shown): ")
    if len(first) < 8:
        print("   Too short: at least 8 characters.")
        continue
    if getpass.getpass("The same password again: ") != first:
        print("   They don't match. Once more.")
        continue
    break

DATA.mkdir(exist_ok=True)
f = DATA / "admin.json"
f.write_text(json.dumps(hash_password(first)), encoding="utf-8")
f.chmod(0o600)
(DATA / "admin-sessions.json").unlink(missing_ok=True)
print("Saved.")
if sys.platform == "darwin":
    r = subprocess.run(["launchctl", "kickstart", "-k", f"gui/{os.getuid()}/com.cleanyourtv.helper"], capture_output=True)
    print("The helper restarted with it." if r.returncode == 0 else "Restart the helper to use it.")
