#!/usr/bin/env bash
# Finds dependencies in the Compose fork's published metadata that name a version of a module
# nobody published.
#
# Usage: fork-dependencies.sh check <fork-version> [<target>]
#
# The fork publishes its Compose to ~/.m2 under org.thisisthepy.compose at a version of its own
# (COMPOSE_AS in build-compose.sh), and every module names its siblings at that same version.
# A sibling that was not published, because the build stopped half way or a module was left off
# the fork's publish-local.sh, does not exist anywhere. The Kotlin toolchain then goes to the
# network for it, fails with "Unable to download checksums of file ...", and records that
# failure in its own cache.
#
# A dependency at the fork's version is published when the module it names is in ~/.m2 at that
# version for every target the depending publication serves. For a target's own publication
# (foundation-macosarm64) that is the dependency's root and its publication for the same target.
# For a root (foundation) it is the dependency's root and its publication for every target the
# root's module was published for here, because the root is one file every target reads. A
# module whose desktop build was published and whose macOS build was not is therefore not
# published as far as a macOS consumer is concerned.
#
#   check   prints each dependency that fails that test and exits 1 if there is any. With a
#           target (macosarm64, desktop), only roots and that target's publications are read.
#
# Only groups under org.thisisthepy.compose are read. Nothing is changed: the fork publishes
# every module its modules name, so nothing has to be pointed anywhere else.
set -euo pipefail

usage() { echo "usage: fork-dependencies.sh check <fork-version> [<target>]" >&2; exit 2; }

mode="${1:-}"
version="${2:-}"
[[ -n "$version" ]] || usage
case "$mode" in
    check)  argument="${3:-}" ;;
    *) usage ;;
esac

repository="$HOME/.m2/repository"
[[ -d "$repository/org/thisisthepy/compose" ]] || exit 0

VERSION="$version" ARGUMENT="$argument" REPOSITORY="$repository" perl -e '
use strict;
use warnings;
use File::Find;
use JSON::PP;

my ($version, $argument, $repository) = @ENV{qw(VERSION ARGUMENT REPOSITORY)};
# The fork's Compose names its JVM target desktop. jvm is left out: the desktop module's own
# publications are desktop-jvm-<os>-<arch>, and reading desktop-jvm as a jvm target would make
# every sibling's desktop-jvm publication look missing.
my @targets = qw(desktop android macosarm64 macosx64 linuxx64 linuxarm64 mingwx64
                 iosarm64 iosx64 iossimulatorarm64 wasmjs js);
my $target_pattern = join "|", @targets;
my $fork_group = qr/^org\.thisisthepy\.compose\./;

sub files_of {
    my ($group, $artifact) = @_;
    (my $path = $group) =~ s{\.}{/}g;
    return "$repository/$path/$artifact/$version/$artifact-$version";
}
sub has_artifact {
    my ($group, $artifact) = @_;
    my $base = files_of($group, $artifact);
    return -s "$base.module" || -s "$base.pom";
}
sub split_target {
    my ($artifact) = @_;
    return $artifact =~ /^(.+)-($target_pattern)$/ ? ($1, $2) : ($artifact, undef);
}

# The targets one publication serves: its own, or for a root every target published beside it.
sub served_targets {
    my ($group, $artifact) = @_;
    my ($base, $target) = split_target($artifact);
    return ($target) if defined $target;
    return grep { has_artifact($group, "$artifact-$_") } @targets;
}

sub is_published {
    my ($group, $artifact, $served) = @_;
    return 0 unless has_artifact($group, $artifact);
    my ($base, $target) = split_target($artifact);
    return 1 if defined $target;
    for my $served_target (@$served) {
        return 0 unless has_artifact($group, "$artifact-$served_target");
    }
    return 1;
}

my @files;
find({ no_chdir => 1, wanted => sub {
    return unless -f $_ && m{/\Q$version\E/[^/]+-\Q$version\E\.(?:pom|module)$};
    push @files, $_;
} }, "$repository/org/thisisthepy/compose");

my @problems;
for my $file (sort @files) {
    my ($group_path, $artifact) = $file =~ m{^\Q$repository\E/(.+)/([^/]+)/\Q$version\E/[^/]+$} or next;
    (my $group = $group_path) =~ s{/}{.}g;
    next unless $group =~ $fork_group;
    my ($base, $own_target) = split_target($artifact);
    next if $argument ne "" && defined $own_target && $own_target ne $argument;
    my @served = served_targets($group, $artifact);

    # Gradle and the Kotlin toolchain read the .module where there is one; the .pom beside it is
    # for Maven and lists the dependencies of every target in one place.
    (my $module_file = $file) =~ s/\.pom$/.module/;
    next if $file =~ /\.pom$/ && -f $module_file;

    open my $in, "<", $file or die "cannot read $file: $!\n";
    local $/;
    my $text = <$in>;
    close $in;

    my $check = sub {
        my ($dependency_group, $dependency) = @_;
        return if $dependency_group !~ $fork_group;
        return if is_published($dependency_group, $dependency, \@served);
        my $relative = $file;
        $relative =~ s{^\Q$repository\E/}{};
        push @problems, "$dependency_group:$dependency:$version, named by $relative";
    };

    if ($file =~ /\.module$/) {
        my $metadata = JSON::PP->new->decode($text);
        my %served = map { $_ => 1 } @served;
        for my $variant (@{ $metadata->{variants} || [] }) {
            # A root lists a variant for every target the build configured; only the targets
            # published here are ones a consumer reaches. Constraints name a module without
            # asking for it (an atomic group names every module in it), so only dependencies count.
            my ($target) = ($variant->{name} // "") =~ /^([a-z][A-Za-z0-9]*?)(?:Api|Runtime|Sources|Metadata|Javadoc)Elements/;
            if (defined $target && !defined $own_target) {
                $target = lc $target;
                # The shared metadata variant lists the dependencies of every source set, a
                # target nobody built here included (ui-uikit, for iOS). The toolchain compiles
                # a platform module against the target publications, so it is left out.
                next unless $served{$target};
            }
            for my $dependency (@{ $variant->{dependencies} || [] }) {
                my $requested = $dependency->{version} || {};
                next unless grep { defined $_ && $_ eq $version } @$requested{qw(requires strictly prefers)};
                $check->($dependency->{group}, $dependency->{module});
            }
        }
    } else {
        # Maven: every <dependency> at the fork version, outside the managed versions.
        $text =~ s{<dependencyManagement>.*?</dependencyManagement>}{}gs;
        while ($text =~ m{<dependency>(.*?)</dependency>}gs) {
            my $body = $1;
            my ($dependency_group) = $body =~ m{<groupId>\s*([^<\s]+)\s*</groupId>};
            my ($dependency) = $body =~ m{<artifactId>\s*([^<\s]+)\s*</artifactId>};
            next unless defined $dependency_group && defined $dependency;
            $check->($dependency_group, $dependency) if $body =~ m{<version>\s*\Q$version\E\s*</version>};
        }
    }
}

if (@problems) {
    my %seen;
    print "$_\n" for grep { !$seen{$_}++ } @problems;
    exit 1;
}
'
