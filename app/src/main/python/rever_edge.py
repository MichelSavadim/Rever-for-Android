"""Small bridge to the upstream edge-tts package bundled in the APK."""

import asyncio
import json
import os
import uuid

import edge_tts


def list_voices_json():
    voices = asyncio.run(edge_tts.list_voices())
    return json.dumps([
        {"id": item["ShortName"], "language": item["Locale"],
         "gender": item.get("Gender", "")}
        for item in voices if item.get("ShortName") and item.get("Locale")
    ])


def save_mp3(text, voice, destination):
    partial = destination + "." + uuid.uuid4().hex + ".part"
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    try:
        asyncio.run(edge_tts.Communicate(text, voice=voice).save(partial))
        if os.path.getsize(partial) == 0:
            raise RuntimeError("O serviço Edge TTS não retornou áudio.")
        os.replace(partial, destination)
    finally:
        if os.path.exists(partial):
            os.remove(partial)
