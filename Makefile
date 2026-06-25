# grafana-mcp — build / install / register workflows for the stdio MCP server.

-include .env
export GRAFANA_TOKEN
export GRAFANA_URL

INSTALL_DIR      ?= $(HOME)/bin/mcp
MCP_SERVER_NAME  ?= grafana
ARTIFACT_NAME    := grafana-mcp
BOOT_JAR         := target/$(ARTIFACT_NAME).jar
EXEC_JAR         := target/$(ARTIFACT_NAME)-exec.jar
INSTALLED_BINARY := $(INSTALL_DIR)/$(ARTIFACT_NAME).jar

.PHONY: help
help: ## list targets
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  %-12s %s\n", $$1, $$2}'

.PHONY: jar
jar: ## build the self-executing fat jar
	./mvnw -q clean package -DskipTests
	@echo "✓ $(EXEC_JAR)  ($$(du -h $(EXEC_JAR) | cut -f1))"

.PHONY: install
install: ## copy the executable jar into $(INSTALL_DIR)
	@test -f $(EXEC_JAR) || (echo "✗ $(EXEC_JAR) not found — run 'make jar' first" >&2; exit 1)
	@mkdir -p $(INSTALL_DIR)
	cp $(EXEC_JAR) $(INSTALLED_BINARY)
	chmod +x $(INSTALLED_BINARY)
	@echo "✓ installed: $(INSTALLED_BINARY)"

.PHONY: register
register: ## register the installed jar with Claude Code (user scope) incl. GRAFANA_* env
	@test -x $(INSTALLED_BINARY) || (echo "✗ $(INSTALLED_BINARY) not found — run 'make install' first" >&2; exit 1)
	@test -n "$(GRAFANA_TOKEN)" || (echo "✗ GRAFANA_TOKEN not set — add it to .env (see .env.example)" >&2; exit 1)
	-claude mcp remove --scope user $(MCP_SERVER_NAME) 2>/dev/null
	claude mcp add --scope user $(MCP_SERVER_NAME) \
		--env GRAFANA_TOKEN=$(GRAFANA_TOKEN) \
		--env GRAFANA_URL=$(GRAFANA_URL) \
		-- $(INSTALLED_BINARY)
	@claude mcp get $(MCP_SERVER_NAME) 2>/dev/null || claude mcp list 2>/dev/null | grep $(MCP_SERVER_NAME) || true

.PHONY: unregister
unregister: ## remove the server from the Claude Code user config
	-claude mcp remove --scope user $(MCP_SERVER_NAME)

.PHONY: ship
ship: jar install register ## jar + install + register, in one shot

.PHONY: verify
verify: ## run full build with all quality gates
	./mvnw clean verify
