with open('arquivo_teste', 'wb') as f:
    f.seek((10 * 1024 * 1024 * 1024) - 1)
    f.write(b'\xff')
