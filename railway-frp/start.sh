#!/bin/sh
set -e
: "${FRP_TOKEN:?Variabel FRP_TOKEN wajib diisi di Railway}"
sed "s/__FRP_TOKEN__/${FRP_TOKEN}/" /etc/frp/frps.toml.template > /etc/frp/frps.toml
exec frps -c /etc/frp/frps.toml
