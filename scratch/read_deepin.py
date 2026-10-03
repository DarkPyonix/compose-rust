from PIL import Image

def analyze(path):
    print(f"\nAnalyzing {path}")
    try:
        img = Image.open(path)
        img = img.convert('RGB')
        w, h = img.size
        # sample some colors from the background and rows
        points = [(10, 10), (w//2, 10), (w//2, 50), (w//2, 100)]
        for p in points:
            if p[0] < w and p[1] < h:
                r, g, b = img.getpixel(p)
                print(f"Pixel {p}: RGB({r},{g},{b})")
    except Exception as e:
        print(f"Error: {e}")

analyze('/Volumes/macMini/darkpyonix/dioxus-compose/docs/references/design-systems/deepin/systemmonitor-processmanage.png')
analyze('/Volumes/macMini/darkpyonix/dioxus-compose/docs/references/design-systems/deepin/229387434-67046402-9116-414a-8eba-48b1126ac08e.png')
