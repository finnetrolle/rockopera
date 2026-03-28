#!/bin/sh
set -eu

require_env() {
    name="$1"
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        echo "Missing required environment variable: $name" >&2
        exit 1
    fi
}

yaml_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

require_env OPENAI_LIKE_API_BASE
require_env OPENAI_LIKE_API_KEY
require_env OPENAI_LIKE_SONNET_MODEL

: "${OPENAI_LIKE_OPUS_MODEL:=$OPENAI_LIKE_SONNET_MODEL}"
: "${OPENAI_LIKE_HAIKU_MODEL:=$OPENAI_LIKE_SONNET_MODEL}"
: "${LITELLM_MASTER_KEY:=sk-rockopera-local}"
: "${LITELLM_SONNET_ALIAS:=rockopera-sonnet}"
: "${LITELLM_OPUS_ALIAS:=rockopera-opus}"
: "${LITELLM_HAIKU_ALIAS:=rockopera-haiku}"

config_path="/tmp/litellm-config.yaml"

cat > "$config_path" <<EOF
model_list:
  - model_name: "$(yaml_escape "$LITELLM_SONNET_ALIAS")"
    litellm_params:
      model: "$(yaml_escape "$OPENAI_LIKE_SONNET_MODEL")"
      api_base: os.environ/OPENAI_LIKE_API_BASE
      api_key: os.environ/OPENAI_LIKE_API_KEY
  - model_name: "$(yaml_escape "$LITELLM_OPUS_ALIAS")"
    litellm_params:
      model: "$(yaml_escape "$OPENAI_LIKE_OPUS_MODEL")"
      api_base: os.environ/OPENAI_LIKE_API_BASE
      api_key: os.environ/OPENAI_LIKE_API_KEY
  - model_name: "$(yaml_escape "$LITELLM_HAIKU_ALIAS")"
    litellm_params:
      model: "$(yaml_escape "$OPENAI_LIKE_HAIKU_MODEL")"
      api_base: os.environ/OPENAI_LIKE_API_BASE
      api_key: os.environ/OPENAI_LIKE_API_KEY
litellm_settings:
  master_key: "$(yaml_escape "$LITELLM_MASTER_KEY")"
EOF

if [ "${ROCKOPERA_LITELLM_PRINT_CONFIG:-0}" = "1" ]; then
    cat "$config_path"
    exit 0
fi

exec litellm --config "$config_path" --host 0.0.0.0 --port 4000
