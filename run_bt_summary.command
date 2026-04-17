#!/bin/zsh

set -euo pipefail

cd /Users/sam/code/BT_Android_agent
python3 /Users/sam/code/BT_Android_agent/tools/adb_bt_summary.py "$@"

echo
read -r "?Press Enter to close..."
