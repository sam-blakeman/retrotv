# Bezel art

`make_bezels.py` draws all bezel artwork procedurally (needs Python with numpy + Pillow, Windows fonts).

Regenerate the Retro TV sets straight into the app's assets:

    python make_bezels.py ../app/src/main/assets/bezels tvcrt tvwide

This rewrites `tv*_{wide,square,tall}.png` and updates `params.json`. The `tall` art is the
pocket-TV look used on the cover screen held upright.
