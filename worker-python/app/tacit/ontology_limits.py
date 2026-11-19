"""Limits shared by the publication readiness check and the native HTTP transport."""
import json

MODEL_LIMIT = 2 * 1024 * 1024
TERM_LIMIT = 512


def model_bytes(model):
    return json.dumps(model, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
