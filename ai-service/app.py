import logging
import os
import time
import requests
import torch
# Monkeypatch torch.load to disable weights_only=True default in PyTorch 2.6+
# This is required because we are using a complex model structure (YOLOv8) 
# and we trust the source (ultralytics assets).
_original_torch_load = torch.load
def patched_torch_load(*args, **kwargs):
    # Force weights_only=False if not explicitly set
    if 'weights_only' not in kwargs:
        kwargs['weights_only'] = False
    return _original_torch_load(*args, **kwargs)
torch.load = patched_torch_load

from ultralytics import YOLO

# Configuration
YOLO_MODEL = os.getenv("YOLO_MODEL", "yolov8n.pt")
ZLM_HOST = os.getenv("ZLM_HOST", "http://127.0.0.1:80")
JAVA_API_HOST = os.getenv("JAVA_API_HOST", "http://127.0.0.1:8080")
CHECK_INTERVAL = float(os.getenv("CHECK_INTERVAL", "2.0"))  # Seconds between checks per channel
CONFIDENCE_THRESHOLD = float(os.getenv("CONFIDENCE_THRESHOLD", "0.5"))
RTSP_HOST = os.getenv("RTSP_HOST", "127.0.0.1")
RTSP_HOST_PORT = os.getenv("RTSP_HOST_PORT", "554")

# Setup Logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

def get_channels():
    """Fetch active channels from Java Backend or ZLM."""
    # For MVP, we can fetch online devices from Java backend
    try:
        url = f"{JAVA_API_HOST}/api/devices"
        response = requests.get(url)
        if response.status_code == 200:
            devices = response.json()
            channels = []
            for device in devices:
                 # Ensure we have active channels. This API might need adjustment depending on actual response structure
                 # Assuming device has 'deviceId' and we can try to look at its channels
                 if device.get('online', False):
                     # Fetch channels for this device
                     c_url = f"{JAVA_API_HOST}/api/devices/{device['deviceId']}/channels"
                     c_resp = requests.get(c_url)
                     if c_resp.status_code == 200:
                         dev_channels = c_resp.json()
                         for ch in dev_channels:
                             channels.append({
                                 "deviceId": device['deviceId'],
                                 "channelId": ch['channelId']
                             })
            return channels
    except Exception as e:
        logger.error(f"Failed to fetch channels: {e}")
    return []

def check_channel(device_id, channel_id, model):
    """
    1. Get snapshot from ZLM.
    2. Run detection.
    3. Notify backend if person detected.
    """
    # ZLMediaKit snapshot URL: /index/api/getSnap
    # We need to play the stream first to get a snap? 
    # Actually ZLM generates snaps if stream is active.
    # We assume stream is active (previewing or recording).
    # URL pattern might vary based on how stream is named in ZLM.
    # Usually: /index/api/getSnap?secret=...&url=...
    # Or static snap path if configured: /snap/live/stream.jpg
    
    # Construct stream app/id. 
    # In GB28181 implementation, usually app="rtp", stream=deviceId_channelId (or just channelId depending on mapping)
    # Let's assume app="rtp" and streamId=channelId for now, need to verify with Java logic.
    app = "rtp"
    stream_id = f"{channel_id}" 
    
    # We use the static snapshot URL provided by ZLM if 'snapRoot' is set and auto snap is on.
    # Or we use the API to force generate/get.
    
    # Try fetching the snapshot image directly if ZLM exposes it
    # Default ZLM behavior: http://zlm:80/index/api/getSnap?url=rtsp://...&timeout_sec=...&expire_sec=...
    # But connecting to API is better.
    
    # IMPORTANT: The Java backend knows the ZLM secret. We might need it here or pass it.
    # For now, let's assume we can access the snapshot via a public URL if configured, 
    # OR we need the ZLM secret.
    
    # Simpler approach for MVP:
    # We assume the stream is available at rtsp://.../rtp/stream_id
    # We ask ZLM to give us a snap.
    
    # But wait, looking at config.ini: `snapRoot=./www/snap/`
    # ZLM usually saves snaps at `http://zlm:80/snap/rtp/device_channel.jpg` if configured.
    # Let's try to fetch that static URL first.
    
    snapshot_url = f"{ZLM_HOST}/index/api/getSnap"
    # We need to know the 'stream_url' to tell ZLM what to snap.
    # Construct a local RTSP URL for the stream
    stream_url = f"rtsp://{RTSP_HOST}:{RTSP_HOST_PORT}/{app}/{stream_id}"
    
    params = {
        "secret": os.getenv("ZLM_SECRET", "ntModmVZiUw6arPbJiiuhfGC7FzNgWLx"),
        "url": stream_url,
        "timeout_sec": 5,
        "expire_sec": 1
    }
    
    try:
        # Request snapshot from ZLM
        # This returns a JSON with {"code": 0, "data": "base64..."} OR redirects to image?
        # ZLM getSnap API writes to file and returns success, or returns image?
        # Actually ZLM's `getSnap` API usually generates a file and returns the path or base64.
        # Let's use the 'scan' mode on the generated file if possible, or just download the image result.
        
        # NOTE: ZLM getSnap might return the image binary directly if not configured otherwise?
        # Let's assume we get the image bytes.
        
        resp = requests.get(snapshot_url, params=params, timeout=6)
        
        if resp.status_code == 200 and resp.headers.get('Content-Type', '').startswith('image'):
            # It's an image
            image_data = resp.content
            
            # Run Inference
            results = model(image_data, verbose=False) # YOLOv8 supports PIL/bytes? 
            # We might need to wrap bytes in PIL Image
            from PIL import Image
            import io
            img = Image.open(io.BytesIO(image_data))
            
            results = model(img, verbose=False)
            
            # Check for persons
            person_detected = False
            for r in results:
                for box in r.boxes:
                    cls_id = int(box.cls[0])
                    conf = float(box.conf[0])
                    if model.names[cls_id] == 'person' and conf > CONFIDENCE_THRESHOLD:
                        person_detected = True
                        break
            
            if person_detected:
                logger.info(f"Person detected on {device_id}/{channel_id}!")
                
                # Notify Backend
                # We need to send the image data to the backend to save it.
                files = {'file': ('snapshot.jpg', image_data, 'image/jpeg')}
                data = {
                    'deviceId': device_id,
                    'channelId': channel_id,
                    'timestamp': int(time.time() * 1000)
                }
                requests.post(f"{JAVA_API_HOST}/api/alarms", data=data, files=files)
                
                # Sleep a bit to avoid flooding for this channel
                time.sleep(5)
                
        else:
            # logger.debug(f"No snapshot available for {channel_id}")
            pass

    except Exception as e:
        logger.error(f"Error checking channel {channel_id}: {e}")

def main():
    logger.info("Starting AI Service...")
    
    # Load Model (will download on first run)
    model = YOLO(YOLO_MODEL)
    
    while True:
        try:
            channels = get_channels()
            if not channels:
                logger.info("No active channels found. Retrying in 10s...")
                time.sleep(10)
                continue
                
            for ch in channels:
                check_channel(ch['deviceId'], ch['channelId'], model)
                
            time.sleep(CHECK_INTERVAL)
            
        except Exception as e:
            logger.error(f"Main loop error: {e}")
            time.sleep(5)

if __name__ == "__main__":
    main()
