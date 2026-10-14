FROM python:3.13-slim
RUN groupadd -g 10001 care && useradd -u 10001 -g care -M care && mkdir /data && chown care:care /data
WORKDIR /app
COPY sync_server.py backup.py ./
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8080
CMD ["python", "sync_server.py"]
