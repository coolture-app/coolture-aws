import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

os.environ.setdefault("COGNITO_ISSUER", "https://cognito-idp.eu-central-1.amazonaws.com/eu-central-1_test")
os.environ.setdefault("COGNITO_CLIENT_ID", "test-client")
