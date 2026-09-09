# grafana-mcp — build / install / register workflows for the stdio MCP server.
#
# No Grafana credentials pass through here. Base URLs and tokens live in the user-editable
# config file ($(CONFIG_FILE)); registering the server needs neither, so a token never reaches
# a Makefile variable, a process listing, or .claude.json.

-include .env
export GRAFANA_MCP_CONFIG
export GRAFANA_LOKI_DATASOURCE_UID
export GRAFANA_PROMETHEUS_DATASOURCE_UID
export GRAFANA_TEMPO_DATASOURCE_UID

# Must track EnvironmentStore's own resolution, which prefers $GRAFANA_MCP_CONFIG.
CONFIG_FILE      ?= $(or $(GRAFANA_MCP_CONFIG),$(HOME)/.grafana-mcp-server/config.json)
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
install: jar ## build, then atomically install the executable jar into $(INSTALL_DIR)
	@mkdir -p $(INSTALL_DIR)
# Copy to a sibling and rename rather than cp'ing straight over the destination.
# A plain cp rewrites that inode in place, so an MCP server still running off the
# old jar starts failing with a bare NoClassDefFoundError on every class the JVM
# had not yet loaded, while already-loaded ones keep working. rename(2) gives the
# new jar its own inode and leaves a running process safely on the old one.
	cp $(EXEC_JAR) $(INSTALLED_BINARY).new
	chmod +x $(INSTALLED_BINARY).new
	mv -f $(INSTALLED_BINARY).new $(INSTALLED_BINARY)
	@echo "✓ installed: $(INSTALLED_BINARY)"
	@echo "  Restart your MCP client to load it; a running server keeps the old jar."

.PHONY: register
register: ## register the installed jar with Claude Code (user scope)
	@test -x $(INSTALLED_BINARY) || (echo "✗ $(INSTALLED_BINARY) not found — run 'make install' first" >&2; exit 1)
	@test -s $(CONFIG_FILE) || echo "! $(CONFIG_FILE) is missing — no Grafana is configured yet, so every tool will fail. Run 'make config' to scaffold it." >&2
	-claude mcp remove --scope user $(MCP_SERVER_NAME) 2>/dev/null
	claude mcp add --scope user $(MCP_SERVER_NAME) -- $(INSTALLED_BINARY)
	@claude mcp get $(MCP_SERVER_NAME) 2>/dev/null || claude mcp list 2>/dev/null | grep $(MCP_SERVER_NAME) || true

.PHONY: config
config: ## scaffold the environments config file (never overwrites an existing one)
	@test ! -e $(CONFIG_FILE) || (echo "✗ $(CONFIG_FILE) already exists — edit it directly" >&2; exit 1)
	@mkdir -p $(dir $(CONFIG_FILE))
	@printf '%s\n' \
		'{' \
		'  "environments": {' \
		'    "production": { "baseUrl": "https://grafana.example.com", "token": "glsa_…" },' \
		'    "local":      { "baseUrl": "http://localhost:3000",       "token": "glsa_…" }' \
		'  }' \
		'}' > $(CONFIG_FILE)
	@chmod 600 $(CONFIG_FILE)
	@echo "✓ wrote $(CONFIG_FILE) (chmod 600) — edit it with real base URLs and service-account tokens"

.PHONY: unregister
unregister: ## remove the server from the Claude Code user config
	-claude mcp remove --scope user $(MCP_SERVER_NAME)

.PHONY: ship
ship: jar install register ## jar + install + register, in one shot

.PHONY: verify
verify: ## run full build with all quality gates
	./mvnw clean verify
