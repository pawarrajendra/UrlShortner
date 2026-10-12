"""Upgrade the saved Phase 2 app/V1 to Phase 3/V2 on an isolated retained volume."""

from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

spec = spec_from_file_location("foundation_smoke", Path(__file__).with_name("phase1-smoke.py"))
smoke = module_from_spec(spec)
spec.loader.exec_module(smoke)

if __name__ == "__main__":
    smoke.main(core=True, brownfield=True)
