# RDP via Railway (tanpa Tailscale) — server frp

Paket ini adalah **server frp (frps)** yang dideploy SEKALI ke akun Railway
kamu. Setelah aktif, workflow `rdp-6h.yml` dengan `akses = railway` membuat
sesi GitHub RDP bisa dihubungi dari HP lewat endpoint publik Railway —
tanpa Tailscale, tanpa port forwarding.

Alur: `HP → TCP proxy Railway #2 (host:port) → frps:13389 → frpc di runner
GitHub → 127.0.0.1:3389`. Kontrol frp lewat TCP proxy #1 (→ frps:7000).

## Langkah deploy (sekali saja)

1. Railway → New Project → Deploy from GitHub repo → pilih repo XyRDP ini,
   lalu di service Settings set **Root Directory = `railway-frp`**
   (build memakai Dockerfile di folder ini).
2. Service → Variables: tambah `FRP_TOKEN` = kata sandi acak panjang
   (samakan nanti dengan secret GitHub di bawah).
3. Service → Settings → Networking → **TCP Proxy**: tambahkan dua baris:
   - internal port **7000** → catat endpoint publiknya (mis.
     `roundhouse.proxy.rlwy.net:12345`) → ini **endpoint kontrol**
   - internal port **13389** → catat endpoint publiknya (mis.
     `roundhouse.proxy.rlwy.net:23456`) → ini **endpoint RDP untuk HP**
4. Di repo XyRDP (GitHub) → Settings → Secrets and variables → Actions:
   - Secret **`RAILWAY_FRP_TOKEN`** = nilai `FRP_TOKEN` yang sama
   - Variable **`RAILWAY_FRP_SERVER`** = endpoint kontrol (host:port)
   - Variable **`RAILWAY_REMOTE_PORT`** = `13389` (default; boleh dikosongkan)

## Menjalankan sesi

Dari aplikasi XyDesk Remote (XyRDP) pilih akses **Railway** (atau dari
GitHub Actions → Run workflow): isi `railway_host` & `railway_port` dengan
endpoint RDP publik (langkah 3 baris kedua). Status sesi akan menerbitkan
alamat itu sebagai tujuan koneksi RDP.

## Catatan biaya & batas

- Railway mengenakan biaya berbasis pemakaian; paket Hobby (US$5/bulan,
  termasuk kredit pemakaian) cukup untuk relay RDP komunitas kecil.
  Tidak ada kewajiban — matikan service Railway kapan saja dan jalur
  Tailscale tetap tersedia.
- frps hanya mengizinkan remote port 13389 (`allowPorts`), dan frpc wajib
  token. Jangan bagikan `FRP_TOKEN`.
- Sesi GitHub Actions tetap dibatasi 6 jam oleh GitHub; Railway hanya
  jalur masuknya.
