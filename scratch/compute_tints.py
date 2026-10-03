def hex_to_rgb(hex_str):
    h = hex_str.strip('#').zfill(6)
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def rgb_to_hex(r, g, b):
    return f"{int(r):02x}{int(g):02x}{int(b):02x}"

def blend(fg, bg, alpha):
    return tuple(fg[i]*alpha + bg[i]*(1-alpha) for i in range(3))

accents = {
    'Primary': ('0081ff', '3ba2ff'),
    'Secondary': ('f2a13c', 'ffb964'),
    'Tertiary': ('7a5bd6', '9f8ae3')
}

bg_light = hex_to_rgb('ffffff')
bg_dark = hex_to_rgb('1a1a1a')

for name, (light_acc, dark_acc) in accents.items():
    l_acc = hex_to_rgb(light_acc)
    d_acc = hex_to_rgb(dark_acc)
    
    # Adwaita uses roughly 15% in light and 15% in dark?
    # Let's use 15% light, 20% dark
    c_light = blend(l_acc, bg_light, 0.15)
    c_dark = blend(d_acc, bg_dark, 0.20)
    
    # OnContainer is usually a dark version of the accent (blend with black) in light
    # and a light version (blend with white) in dark
    on_c_light = blend(l_acc, hex_to_rgb('000000'), 0.5) 
    on_c_dark = blend(d_acc, hex_to_rgb('ffffff'), 0.5)
    
    print(f"        {name}Container: 0x{rgb_to_hex(*c_light)} / 0x{rgb_to_hex(*c_dark)},")
    print(f"        On{name}Container: 0x{rgb_to_hex(*on_c_light)} / 0x{rgb_to_hex(*on_c_dark)},")

