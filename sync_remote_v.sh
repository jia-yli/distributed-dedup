#!/bin/bash

# Variables
TARGET_FOLDER="generated_rtl"  # Replace with the path to your target folder
ARCHIVE_NAME="generated_rtl.tar.gz"
SERVER_USER="jiayli"
SERVER_IP="alveo-build"
# SERVER_PATH="/home/$SERVER_USER/projects/dedup"
# EXTRACT_PATH="/home/$SERVER_USER/projects/dedup"
SERVER_PATH="/home/$SERVER_USER/projects/coyote-rdma/hw/hdl/operators/examples/dedup"
EXTRACT_PATH="/home/$SERVER_USER/projects/coyote-rdma/hw/hdl/operators/examples/dedup"

echo "Generating Verilog"
rm -rf "$TARGET_FOLDER"
./mill hwsys.runMain dedup.GenDedupSys

echo "Move to Server $SERVER_IP"
# Step 1: Compress the target folder
# The 'f' option ensures that if the file exists, it is overwritten
tar -czvf "$ARCHIVE_NAME" "$TARGET_FOLDER"

# Step 2: SCP the file to the server
# SCP overwrites the file at the destination by default
scp "$ARCHIVE_NAME" "$SERVER_USER@$SERVER_IP:$SERVER_PATH"

# Step 3: Connect to the server, clean up the old files and unzip the new file
ssh "$SERVER_USER@$SERVER_IP" "\
    rm -rf $EXTRACT_PATH/$TARGET_FOLDER && \
    tar -xzvf $SERVER_PATH/$ARCHIVE_NAME -C $EXTRACT_PATH"

echo "Process completed"
