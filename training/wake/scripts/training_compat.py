"""Small process-local compatibility helpers for the legacy training stack."""

from __future__ import annotations

import sys


def apply_scipy_acoustics_compatibility() -> bool:
    """Restore the removed SciPy alias used only during acoustics import.

    The legacy acoustics package imports ``sph_harm``. Modern SciPy renamed it
    to ``sph_harm_y`` and swaps the first two angular arguments. This process
    local alias unblocks the openWakeWord noise-generation import path without
    editing SciPy or acoustics on disk.
    """

    import scipy.special

    if hasattr(scipy.special, "sph_harm"):
        return False
    if not hasattr(scipy.special, "sph_harm_y"):
        raise RuntimeError("This SciPy version provides neither sph_harm nor sph_harm_y.")

    def sph_harm(
        m: int,
        n: int,
        theta: object,
        phi: object,
        *args: object,
        **kwargs: object,
    ) -> object:
        return scipy.special.sph_harm_y(n, m, phi, theta, *args, **kwargs)

    scipy.special.sph_harm = sph_harm
    return True


def remove_unused_speechbrain_lazy_redirects() -> tuple[str, ...]:
    """Remove optional SpeechBrain redirects from this training process.

    SpeechBrain 1.1 registers deprecated optional integrations as lazy modules
    in ``sys.modules``. PyTorch's optimizer initialization inspects module
    ``__file__`` attributes, which otherwise forces those unused integrations
    to import. The openWakeWord feature-array benchmark needs only the real
    SpeechBrain audio helpers already imported by ``openwakeword.data``.
    """

    from speechbrain.utils.importutils import LazyModule

    removed = []
    for name, module in list(sys.modules.items()):
        if name.startswith("speechbrain.") and isinstance(module, LazyModule):
            sys.modules.pop(name, None)
            removed.append(name)
    return tuple(removed)
