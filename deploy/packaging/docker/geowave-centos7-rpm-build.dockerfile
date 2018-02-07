FROM centos:centos7

RUN yum -y install asciidoc rpm-build unzip xmlto zip wget \
    ruby-devel autoconf gcc make rpm-build rubygems automake \
    java-1.8.0-openjdk java-1.8.0-openjdk-devel libtool && \
    yum clean all

RUN gem install --no-ri --no-rdoc fpm
 
