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
#   the measuring     a server that draws into memory, a window manager, a way to take a
#                     picture of it, a way to move a pointer, and a way to look. The
#                     manager is not decoration: an edge is dragged through one, and the
#                     protocol that holds a frame until the drawing has caught up is a
#                     conversation with one, so without it there is nothing to measure.
#                     The VNC server is how someone on the machine running this container
#                     sees the window with their own eyes, through the screen sharing
#                     every Mac already has.
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jdk ca-certificates curl unzip git bash file \
        build-essential pkg-config \
        libx11-dev libxext-dev libxrandr-dev libgl1-mesa-dev libglu1-mesa-dev \
        libfontconfig1-dev libfreetype-dev \
        xvfb x11-apps xdotool imagemagick mesa-utils libgl1-mesa-dri \
        openbox x11vnc \
    && rm -rf /var/lib/apt/lists/*

# Rust, so that a sample can be linked against the archive the renderer builds into and
# actually run. Installed by rustup rather than from the distribution, because a
# distribution carries whatever it froze and this links against a checkout's own
# Cargo.lock.
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
        | sh -s -- -y --default-toolchain stable --profile minimal
ENV PATH="/root/.cargo/bin:$PATH"

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

# Mesa draws with the processor rather than looking for a card that is not in a container.
ENV LIBGL_ALWAYS_SOFTWARE=1
ENV GALLIUM_DRIVER=llvmpipe

# The caches the toolchain and Gradle fill are mounted from the host rather than built into
# the image, so that a container is cheap to throw away and a build is not repeated.
VOLUME ["/root/.m2", "/root/.gradle", "/root/.konan", "/root/.cache"]

WORKDIR /work
