FROM python:3.11-slim

RUN useradd --create-home --uid 10001 appuser \
    && pip install --no-cache-dir data-formulator==0.7.0

COPY patch_workspace_deeplink.py /tmp/patch_workspace_deeplink.py
RUN python /tmp/patch_workspace_deeplink.py /usr/local/lib/python3.11/site-packages/data_formulator/dist/DataFormulator.js \
    && rm /tmp/patch_workspace_deeplink.py

USER appuser
ENV HOME=/home/appuser
EXPOSE 5567

ENTRYPOINT ["python", "-m", "data_formulator", "--host", "0.0.0.0", "--port", "5567"]
