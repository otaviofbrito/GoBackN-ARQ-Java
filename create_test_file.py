with open('test_file', 'wb') as f:
    f.seek((10 * 1024 * 1024) - 1)
    f.write(b'\xff')
