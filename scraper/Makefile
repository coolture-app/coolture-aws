# sam build runs this for aws/scraper.yaml. It packs the scraper's modules and their dependencies for an arm64
# Lambda, without the tests or the gitignored snapshot, which a plain CodeUri would take along.
PYTHON ?= $(if $(filter Windows_NT,$(OS)),python,python3)

build-ScraperFunction:
	$(PYTHON) -c "import glob, shutil, sys; [shutil.copy(f, sys.argv[1]) for f in glob.glob('*.py')]" "$(ARTIFACTS_DIR)"
	$(PYTHON) -m pip install -q --disable-pip-version-check -r requirements.txt tzdata --target "$(ARTIFACTS_DIR)" \
		--platform manylinux2014_aarch64 --only-binary=:all: --python-version 3.13 --implementation cp
