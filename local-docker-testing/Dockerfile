FROM quay.io/semoss/semoss-dev:6.0.0-SNAPSHOT-ubuntu22-latest
#FROM quay.io/semoss/semoss-dev:6.0.0-SNAPSHOT-ubi8-latest

USER root

# Delete the current Monolith (but keep the web.xml)
# And replace with the local development Monolith
RUN cp $TOMCAT_HOME/webapps/Monolith/WEB-INF/web.xml /opt/original_web.xml && \
    rm -rf $TOMCAT_HOME/webapps/Monolith

COPY --chown=default:root target/monolith-*.war $TOMCAT_HOME/webapps/Monolith.war

# Extract the WAR file and replace web.xml with original
# Also make sure ownership is set properly
RUN cd $TOMCAT_HOME/webapps && \
    mkdir Monolith && \
    cd Monolith && \
    unzip ../Monolith.war && \
    cp /opt/original_web.xml WEB-INF/web.xml && \
    rm ../Monolith.war && \
    chown -R default:root $TOMCAT_HOME/webapps/Monolith

# ---------------------------------------------------------------------------
# Node.js runtime for the agent-only ExecuteNodeCode execution environment.
# Mirrors the layer in Semoss/docker/Dockerfile.* finals; needed here until
# the published semoss-dev base carries node + /opt/semosshome/js itself.
# target/docker-js is staged from ../Semoss/js by createLocalDockerScript.sh
# (never with node_modules - npm ci below installs the linux dependencies).
ARG NODE_VERSION=24.21.0
ENV NODE_HOME=/opt/node
RUN set -eux; \
	case "$(uname -m)" in \
		x86_64) nodeArch=x64 ;; \
		aarch64|arm64) nodeArch=arm64 ;; \
		*) echo "unsupported architecture $(uname -m) for the node runtime"; exit 1 ;; \
	esac; \
	nodeTar="node-v${NODE_VERSION}-linux-${nodeArch}.tar.gz"; \
	cd /tmp; \
	curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/${nodeTar}"; \
	curl -fsSL -o node-SHASUMS256.txt "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt"; \
	grep " ${nodeTar}" node-SHASUMS256.txt | sha256sum -c -; \
	mkdir -p "/opt/node-v${NODE_VERSION}"; \
	tar -xzf "${nodeTar}" -C "/opt/node-v${NODE_VERSION}" --strip-components=1; \
	ln -sfn "/opt/node-v${NODE_VERSION}" /opt/node; \
	rm -f "${nodeTar}" node-SHASUMS256.txt; \
	/opt/node/bin/node --version

COPY --chown=default:root target/docker-js /opt/semosshome/js

# Install the curated packages at build time and seed the RDF_Map opt-in
# default (false) so the compose file can flip it the same way the
# semoss-artifacts env passthrough scripts do (sed against an existing line).
RUN set -eux; \
	cd /opt/semosshome/js/node_env; \
	PATH="/opt/node/bin:$PATH" /opt/node/bin/npm ci --omit=dev --no-audit --no-fund; \
	chown -R default:root /opt/semosshome/js; \
	if ! grep -q "^AGENT_DEFAULT_TOOLS_ENABLE_NODE" /opt/semosshome/RDF_Map.prop; then \
		printf 'AGENT_DEFAULT_TOOLS_ENABLE_NODE\tfalse\n' >> /opt/semosshome/RDF_Map.prop; \
	fi

USER 1001
