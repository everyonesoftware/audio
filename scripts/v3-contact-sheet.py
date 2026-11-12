"""Compose the actual v3 phone captures into a review sheet."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
output = root / 'test-results'
entries = [
    ('welcome', '01  Fresh install'),
    ('connect', '02  Connect to Navidrome'),
    ('libraries', '03  Choose libraries'),
    ('playlists', '04  Choose a playlist'),
    ('player', '05  Player + song rating'),
    ('settings', '06  Server + libraries'),
]
images = [(Image.open(output / f'audio-v3-phone-{name}.png').convert('RGB'), label) for name, label in entries]
width, height = images[0][0].size
padding, gap, title_height = 26, 26, 42
sheet = Image.new('RGB', (padding * 2 + width * 3 + gap * 2, padding * 2 + (height + title_height) * 2 + gap), '#efeee9')
draw = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 17)
except OSError:
    font = ImageFont.load_default()
for index, (image, label) in enumerate(images):
    column, row = index % 3, index // 3
    x = padding + column * (width + gap)
    y = padding + row * (height + title_height + gap)
    draw.text((x, y + 3), label, fill='#282d29', font=font)
    sheet.paste(image, (x, y + title_height))
file = output / 'audio-v3-phone-flow.png'
sheet.save(file)
print(file)
print(f'{sheet.width}x{sheet.height}, {len(images)} actual browser captures')
