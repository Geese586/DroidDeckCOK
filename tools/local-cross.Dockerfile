FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y \
        binutils \
        binutils-aarch64-linux-gnu \
        gcc-aarch64-linux-gnu \
        g++-aarch64-linux-gnu \
        unzip \
    && mkdir -p /src

WORKDIR /src
