build-SSRFunction:
	@test -f dist/server/server.mjs || { echo "Missing dist/server - build the frontend with scripts/deploy.sh <env> frontend" >&2; exit 1; }
	cp -p run.sh $(ARTIFACTS_DIR)/
	cp -rp dist/server dist/browser $(ARTIFACTS_DIR)/
