#!/bin/bash

# Directory path
MODEL_DIR="app/src/main/assets/models"
MODEL_NAME="whisper-large-v3-turbo-q5_0.bin"
MODEL_PATH="$MODEL_DIR/$MODEL_NAME"
URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin?download=true"

# Create directory if it doesn't exist
if [ ! -d "$MODEL_DIR" ]; then
    echo "Creating directory $MODEL_DIR..."
    mkdir -p "$MODEL_DIR"
fi

# Download model if it doesn't exist
if [ ! -f "$MODEL_PATH" ]; then
    echo "Downloading Whisper model: $MODEL_NAME..."
    curl -L "$URL" -o "$MODEL_PATH"
    if [ $? -eq 0 ]; then
        echo "Download completed successfully: $MODEL_PATH"
    else
        echo "Error: Download failed."
        exit 1
    fi
else
    echo "Model $MODEL_NAME already exists in $MODEL_DIR."
fi
