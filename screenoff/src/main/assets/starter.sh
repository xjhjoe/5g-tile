#!/system/bin/sh

base_dir="$(dirname "$0")"
log_path="$base_dir/controller_start.log"
origin_path="$base_dir/ScreenController.dex"
target_path="/data/local/tmp/ScreenController.dex"

{
  echo "===== $(date '+%Y-%m-%d %H:%M:%S') ====="
  echo "stage=starter_begin"
  echo "uid=$(id)"
  echo "pwd=$(pwd)"
  echo "base_dir=$base_dir"
  echo "origin_path=$origin_path"
  echo "target_path=$target_path"

  pm grant com.tile.screenoff.a17 android.permission.WRITE_SECURE_SETTINGS
  echo "pm_grant_exit=$?"

  if [ ! -f "$origin_path" ]; then
    echo "error=origin_dex_missing"
    exit 11
  fi

  ls -l "$origin_path"
  cp -f "$origin_path" "$target_path"
  cp_exit=$?
  echo "cp_exit=$cp_exit"
  if [ "$cp_exit" -ne 0 ]; then
    echo "error=copy_failed"
    exit 12
  fi

  ls -l "$target_path"
  export CLASSPATH="$target_path"
  echo "classpath=$CLASSPATH"
  echo "stage=launch_app_process"

  nohup app_process /system/bin com.tile.screenoff.ScreenController >> "$log_path" 2>&1 &
  controller_pid=$!
  echo "controller_pid=$controller_pid"

  sleep 1
  if kill -0 "$controller_pid" 2>/dev/null; then
    echo "controller_alive_after_1s=1"
  else
    echo "controller_alive_after_1s=0"
  fi
  echo "stage=starter_end"
} >> "$log_path" 2>&1
