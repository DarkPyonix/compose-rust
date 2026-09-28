# A Linux the renderer can be built and run in, for the checks a Mac cannot make.
#
# Linux is a target nobody here has a machine for, and the two things that matter most
# about a window cannot be checked by reading: whether it builds against the system's own
# X11 headers, and whether what it draws keeps up with the edge a hand is dragging. Both
# are answerable in a container with an X server that draws into memory.
#
# x86-64, because that is the target: linuxX64 is what the renderer is built for and what
# it will be run on. This machine emulates it, which is slower than native and is the
# price of checking the thing that ships rather than a near relative of it. The Compose
# build also refuses an arm64 Linux host outright: its API validator answers "Unknown host
# target: linux aarch64" before any of it compiles.
FROM --platform=linux/amd64 ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# Three groups, kept apart so a reader can see what each is for.
#
#   the toolchain     the Kotlin Toolchain wrapper downloads its own compiler and needs a
#                     JDK to run the build with, plus the usual fetch and unpack tools
#   the window        X11, its extensions, and GL: the headers the cinterop definitions
#                     name, and at run time the libraries themselves
#   the measuring     a server that draws into memory, a way to take a picture of it, and
#                     a way to move a pointer, so that a drag can be performed and the
#                     result counted in pixels
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jdk ca-certificates curl unzip git bash file \
        build-essential pkg-config \
        libx11-dev libxext-dev libxrandr-dev libgl1-mesa-dev libglu1-mesa-dev \
        libfontconfig1-dev libfreetype-dev \
        xvfb x11-apps xdotool imagemagick mesa-utils libgl1-mesa-dri \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

# Mesa draws with the processor rather than looking for a card that is not in a container.
ENV LIBGL_ALWAYS_SOFTWARE=1
ENV GALLIUM_DRIVER=llvmpipe

# The caches the toolchain and Gradle fill are mounted from the host rather than built into
# the image, so that a container is cheap to throw away and a build is not repeated.
VOLUME ["/root/.m2", "/root/.gradle", "/root/.konan", "/root/.cache"]

WORKDIR /work
