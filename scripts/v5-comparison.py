from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1] / 'test-results'
entries = [
    ('audio-v5-before-47.png', 'Before · v4'),
    ('audio-v5-player-47.png', 'Revised player · v5'),
    ('audio-v5-rating-47.png', 'Rating popup · v5'),
]
images = [Image.open(root / filename).convert('RGB') for filename, _ in entries]
gap, column = 24, max(220, max(im.width for im in images))
canvas = Image.new('RGB', (column * len(images) + gap * (len(images) + 1), max(im.height for im in images) + 100), '#efeee9')
draw = ImageDraw.Draw(canvas)
try:
    heading = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 16)
    small = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 12)
except OSError:
    heading = small = ImageFont.load_default()
for index, (image, (_, label)) in enumerate(zip(images, entries)):
    x = gap + index * (column + gap)
    draw.text((x, 15), label, font=heading, fill='#282d29')
    draw.text((x, 40), 'Identical 47% phone calibration', font=small, fill='#666d65')
    canvas.paste(image, (x, 70))
output = root / 'audio-v5-same-size-comparison.png'
canvas.save(output)
print(output)
