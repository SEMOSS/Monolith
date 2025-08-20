FROM quay.io/semoss/semoss-dev:5.0.0-alpha-SNAPSHOT-ubuntu22-latest
#FROM quay.io/semoss/semoss-dev:5.0.0-alpha-SNAPSHOT-ubi8-latest

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

USER 1001