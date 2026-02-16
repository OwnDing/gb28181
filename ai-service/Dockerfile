FROM ultralytics/ultralytics:latest

WORKDIR /app

# Install additional dependencies
RUN apt-get update && apt-get install -y ffmpeg libsm6 libxext6 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY app.py .

# Environment variables (Defaults, can be overridden)
ENV JAVA_API_HOST=http://host.docker.internal:8080
ENV ZLM_HOST=http://host.docker.internal:80
ENV ZLM_SECRET=ntModmVZiUw6arPbJiiuhfGC7FzNgWLx
ENV CHECK_INTERVAL=2.0
ENV RTSP_HOST=127.0.0.1
ENV AUTH_USERNAME=admin
ENV AUTH_PASSWORD=admin123

CMD ["python", "app.py"]
