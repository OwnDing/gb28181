import logging
import os
import time
import requests
import torch
from PIL import Image
from io import BytesIO
import cv2
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

# Auth Configuration
AUTH_USERNAME = os.getenv("AUTH_USERNAME", "admin")
AUTH_PASSWORD = os.getenv("AUTH_PASSWORD", "admin123")
AUTH_TOKEN = None

def login():
    """Login to Java Backend to get token."""
    global AUTH_TOKEN
    try:
        url = f"{JAVA_API_HOST}/api/auth/login"
        data = {"username": AUTH_USERNAME, "password": AUTH_PASSWORD}
        logger.info(f"Logging in to {url} as {AUTH_USERNAME}...")
        resp = requests.post(url, json=data, timeout=5)
        if resp.status_code == 200:
            result = resp.json()
            if result.get("code") == 200 or result.get("code") == 0:
                AUTH_TOKEN = result["data"]["token"]
                logger.info("Login successful, token obtained.")
                return True
            else:
                logger.error(f"Login failed: {result.get('message')}")
        else:
            logger.error(f"Login failed: Status {resp.status_code}, Body: {resp.text}")
    except Exception as e:
        logger.error(f"Login exception: {e}")
    return False

def get_headers():
    if not AUTH_TOKEN:
        if not login():
            return {}
    return {"Authorization": f"Bearer {AUTH_TOKEN}"}

def get_channels():
    """Fetch active channels from Java Backend or ZLM."""
    # For MVP, we can fetch online devices from Java backend
    try:
        url = f"{JAVA_API_HOST}/api/devices"
        logger.info(f"Fetching devices from: {url}")
        
        headers = get_headers()
        if not headers:
            return []

        response = requests.get(url, headers=headers, timeout=5) # Add timeout
        
        # Handle token expiration
        if response.status_code == 401:
            logger.info("Token expired, re-logging in...")
            if login():
                headers = get_headers()
                response = requests.get(url, headers=headers, timeout=5)
            else:
                return []

        if response.status_code == 200:
            # Check for standard API result wrapper
            result = response.json()
            # If backend wraps response in ApiResult (code, msg, data)
            if isinstance(result, dict) and "data" in result:
                 devices = result["data"]
            else:
                 devices = result # Plain list?
            
            if not isinstance(devices, list):
                logger.error(f"Unexpected devices response format: {type(devices)}")
                return []
                
            logger.info(f"Got {len(devices)} devices from API")
            channels = []
            for device in devices:
                 # Ensure we have active channels. This API might need adjustment depending on actual response structure
                 # Assuming device has 'deviceId' and we can try to look at its channels
                 logger.info(f"Checking device: {device}")
                 is_online = device.get('online', False)
                 logger.info(f"Device {device.get('deviceId')} online status: {is_online}")
                 
                 if is_online:
                     # Fetch channels for this device
                     # IMPORTANT: The API expects the database ID (long), not the GB28181 deviceId string
                     dev_db_id = device.get('id')
                     c_url = f"{JAVA_API_HOST}/api/devices/{dev_db_id}/channels"
                     try:
                        c_resp = requests.get(c_url, headers=headers, timeout=5)
                        if c_resp.status_code == 200:
                            c_result = c_resp.json()
                            if isinstance(c_result, dict) and "data" in c_result:
                                dev_channels = c_result["data"]
                            else:
                                dev_channels = c_result
                                
                            logger.info(f"Device {device.get('deviceId')} channels resp: {dev_channels}") # LOG CONTENT
                            
                            if not dev_channels:
                                dev_channels = []

                            logger.info(f"Device {device.get('deviceId')} has {len(dev_channels)} channels")
                            for ch in dev_channels:
                                # We only care about channels that are pushing stream? 
                                # For now, take all channels, or maybe filter by status if available
                                channels.append({
                                    "deviceId": device['deviceId'],
                                    "channelId": ch['channelId']
                                })
                        else:
                            logger.error(f"Failed to fetch channels for {device.get('deviceId')}. Status: {c_resp.status_code}, Body: {c_resp.text}")
                     except Exception as e:
                         logger.error(f"Exc fetching channels for {device.get('deviceId')}: {e}")
            logger.info(f"Total active channels found: {len(channels)}")
            return channels
        else:
             logger.error(f"Failed to fetch devices. Status: {response.status_code}, Body: {response.text}")
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
    # Construct stream app/id. 
    # In GB28181 implementation, usually app="rtp", stream=deviceId_channelId (or just channelId depending on mapping)
    app = "rtp"
    
    # Resolve stream_id from ZLM
    # We query getMediaList to find the actual stream name for this channel
    # The stream name might be 'ch' + channelId or just channelId or ssrc
    # We look for a stream that contains the channelId
    stream_id = None
    try:
        media_list_url = f"{ZLM_HOST}/index/api/getMediaList"
        media_params = {
            "secret": os.getenv("ZLM_SECRET", "ntModmVZiUw6arPbJiiuhfGC7FzNgWLx")
        }
        m_resp = requests.get(media_list_url, params=media_params, timeout=5)
        if m_resp.status_code == 200:
            m_data = m_resp.json()
            if m_data.get("code") == 0 and "data" in m_data:
                for item in m_data["data"]:
                    if item.get("app") == app:
                        s_id = item.get("stream", "")
                        # Check if channel_id is part of stream_id (e.g. ch340200... or 340200...)
                        if channel_id in s_id:
                            stream_id = s_id
                            logger.info(f"Using ZLM stream: {item}")
                            if item.get("bytesSpeed", 0) == 0:
                                logger.warning(f"Stream {stream_id} has 0 bytesSpeed! Snapshot might be empty.")
                            break
    except Exception as e:
        logger.error(f"Failed to resolve stream_id from ZLM: {e}")
        
    if not stream_id:
        logger.warning(f"Stream not found in ZLM for channel {channel_id}. Using default.")
        stream_id = f"{channel_id}" # Fallback
    else:
        logger.info(f"Resolved ZLM stream_id: {stream_id} for channel {channel_id}")
    
    # We use the static snapshot URL provided by ZLM if 'snapRoot' is set and auto snap is on.
    # Or we use the API to force generate/get.
    
    # Log start of check
    logger.info(f"Checking channel: {device_id}/{channel_id}")
    
    snapshot_url = f"{ZLM_HOST}/index/api/getSnap"
    # We need to know the 'stream_url' to tell ZLM what to snap.
    # Construct a local RTSP URL for the stream
    stream_url = f"rtsp://{RTSP_HOST}:{RTSP_HOST_PORT}/{app}/{stream_id}"
    logger.info(f"Snapshot URL target: {stream_url}")
    
    # IMPORTANT: The Java backend knows the ZLM secret. We might need it here or pass it.
    # For now, let's assume we can access the snapshot via a public URL if configured, 
    # OR we need the ZLM secret.
    
    # Simpler approach for MVP:
    # We assume the stream is available at rtsp://.../rtp/stream_id
    # We ask ZLM to give us a snap.
    
    # But wait, looking at config.ini: `snapRoot=./www/snap/`
    # ZLM usually saves snaps at `http://zlm:80/snap/rtp/device_channel.jpg` if configured.
    # Let's try to fetch that static URL first.
    
    # Log start of check
    logger.info(f"Checking channel: {device_id}/{channel_id}")
    
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
    
    # Log start of check
    logger.info(f"Checking channel: {device_id}/{channel_id}")
    
    # We need to know the 'stream_url' to tell ZLM what to snap.
    # Construct a local RTSP URL for the stream
    stream_url = f"rtsp://{RTSP_HOST}:{RTSP_HOST_PORT}/{app}/{stream_id}"
    logger.info(f"Snapshot URL target: {stream_url}")
    
    try:
        # Use OpenCV to capture a frame directly from RTSP
        # This avoids ZLM snapshot issues with H.265 or timeouts
        cap = cv2.VideoCapture(stream_url)
        
        # Set buffer size to small to get latest frame? 
        # Actually just read one frame.
        
        if not cap.isOpened():
             logger.warning(f"Failed to open RTSP stream: {stream_url}")
             return

        # Read a frame
        ret, frame = cap.read()
        cap.release()
        
        if ret and frame is not None:
            # frame is BGR numpy array
            # Convert to RGB for PIL
            frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            img = Image.fromarray(frame_rgb)
            
            # Save for debug
            try:
                debug_dir = "/data"
                if not os.path.exists(debug_dir):
                    os.makedirs(debug_dir)
                
                timestamp = int(time.time() * 1000)
                file_path = f"{debug_dir}/{device_id}_{channel_id}_{timestamp}.jpg"
                img.save(file_path)
                logger.info(f"Snapshot saved to {file_path}")
            except Exception as e:
                logger.error(f"Failed to save debug snapshot: {e}")
            
            # Run Inference
            results = model(img, verbose=False)
            
            # Check for persons
            person_detected = False
            detected_classes = []
            for r in results:
                for box in r.boxes:
                    cls_id = int(box.cls[0])
                    conf = float(box.conf[0])
                    class_name = model.names[cls_id]
                    detected_classes.append(f"{class_name}({conf:.2f})")
                    
                    if class_name == 'person' and conf > CONFIDENCE_THRESHOLD:
                        person_detected = True
                        
            logger.info(f"Detection result on {device_id}/{channel_id}: {detected_classes}")
                        
            if person_detected:
                logger.info(f"!! PERSON DETECTED !! Notifying backend...")
                
                # Notify Backend
                # Save image to bytes for upload
                img_byte_arr = BytesIO()
                img.save(img_byte_arr, format='JPEG')
                image_data = img_byte_arr.getvalue()
                
                files = {'file': ('snapshot.jpg', image_data, 'image/jpeg')}
                data = {
                    'deviceId': device_id,
                    'channelId': channel_id,
                    'timestamp': int(time.time() * 1000)
                }
                alarm_resp = requests.post(f"{JAVA_API_HOST}/api/alarms", data=data, files=files)
                if alarm_resp.status_code == 200:
                        logger.info("Backend notified successfully.")
                else:
                        logger.error(f"Failed to notify backend: {alarm_resp.status_code} - {alarm_resp.text}")
                
                # Sleep a bit to avoid flooding for this channel
                time.sleep(5)
                
        else:
            logger.warning(f"Failed to read frame from {stream_url}")

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
