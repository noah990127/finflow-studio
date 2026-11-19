#!/usr/bin/env bash
set -euo pipefail

CONTAINER="${1:-${FINFLOW_ONLYOFFICE_CONTAINER:-finflow-onlyoffice}}"
MAX_DOWNLOAD_BYTES="${ONLYOFFICE_MAX_DOWNLOAD_BYTES:-314572800}"
XLSX_UNCOMPRESSED_LIMIT="${ONLYOFFICE_XLSX_UNCOMPRESSED_LIMIT:-1GB}"
NGINX_BODY_SIZE="${ONLYOFFICE_NGINX_BODY_SIZE:-320m}"

docker exec \
  -e "FINFLOW_MAX_DOWNLOAD_BYTES=$MAX_DOWNLOAD_BYTES" \
  -e "FINFLOW_XLSX_UNCOMPRESSED_LIMIT=$XLSX_UNCOMPRESSED_LIMIT" \
  -e "FINFLOW_NGINX_BODY_SIZE=$NGINX_BODY_SIZE" \
  "$CONTAINER" sh -lc '
    JSON=/var/www/onlyoffice/documentserver/npm/json
    CONFIG=/etc/onlyoffice/documentserver/local.json
    "$JSON" -q -f "$CONFIG" -I -e "this.FileConverter=this.FileConverter||{}; this.FileConverter.converter=this.FileConverter.converter||{}; this.FileConverter.converter.maxDownloadBytes=Number(process.env.FINFLOW_MAX_DOWNLOAD_BYTES); this.FileConverter.converter.inputLimits=[{type:\"docx;dotx;docm;dotm\",zip:{uncompressed:\"150MB\",template:\"*.xml\"}},{type:\"xlsx;xltx;xlsm;xltm\",zip:{uncompressed:process.env.FINFLOW_XLSX_UNCOMPRESSED_LIMIT,template:\"*.xml\"}},{type:\"pptx;ppsx;potx;pptm;ppsm;potm\",zip:{uncompressed:\"150MB\",template:\"*.xml\"}}]"
    sed -i -E "s/client_max_body_size[[:space:]]+[^;]+;/client_max_body_size ${FINFLOW_NGINX_BODY_SIZE};/" /etc/nginx/includes/ds-common.conf
    supervisorctl restart all >/dev/null
  '

printf 'ONLYOFFICE large-file limits applied: download=%s, xlsx-uncompressed=%s, nginx=%s\n' \
  "$MAX_DOWNLOAD_BYTES" "$XLSX_UNCOMPRESSED_LIMIT" "$NGINX_BODY_SIZE"
