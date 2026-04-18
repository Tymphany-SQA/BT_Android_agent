#!/bin/zsh

set -euo pipefail

cd /Users/sam/code/BT_Android_agent2
python3 /Users/sam/code/BT_Android_agent2/tools/adb_bt_summary.py "$@"

echo
read -r "?Press Enter to close..."
