# frps (server frp) untuk Railway — pasangan scripts/setup-railway.ps1.
# Railway memberi dua TCP proxy publik:
#   proxy #1 -> internal 7000  (kontrol frp; isi ke variable repo
#               RAILWAY_FRP_SERVER di XyRDP, format host:port)
#   proxy #2 -> internal 13389 (RDP publik; isi ke input railway_host /
#               railway_port saat menjalankan workflow)
FROM alpine:3.20

ARG FRP_VERSION=0.62.1
RUN apk add --no-cache curl tar \
 && curl -fsSL "https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}/frp_${FRP_VERSION}_linux_amd64.tar.gz" -o /tmp/frp.tar.gz \
 && tar -xzf /tmp/frp.tar.gz -C /tmp \
 && cp "/tmp/frp_${FRP_VERSION}_linux_amd64/frps" /usr/local/bin/frps \
 && rm -rf /tmp/frp*

COPY frps.toml.template /etc/frp/frps.toml.template
COPY start.sh /usr/local/bin/start.sh
RUN chmod +x /usr/local/bin/start.sh

# 7000 = kontrol frp, 13389 = port RDP yang diteruskan (remotePort frpc)
EXPOSE 7000 13389
CMD ["/usr/local/bin/start.sh"]
