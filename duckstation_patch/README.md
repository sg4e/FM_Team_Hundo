# Patching DuckStation for FM Team Hundo

1. Clone the DuckStation repo:
```bash
git clone https://github.com/stenzek/duckstation.git
```

2. Checkout this specific commit for the patch (`v0.1-10998`):
```bash
cd duckstation
git checkout 9b0a4ec559ae83bfdb14685932b036ad7c1701be
```

3. Apply the patch:
```bash
cd ..
python patch_duckstation.py duckstation
```

4. Follow the directions from the DuckStation repo to build DuckStation from source for your operating system.
