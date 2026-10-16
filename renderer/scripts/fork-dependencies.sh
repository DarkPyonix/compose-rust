#!/usr/bin/env bash
# Finds, or repairs, dependencies in the Compose fork's published metadata that name a version
# of a module nobody published.
#
# Usage: fork-dependencies.sh check  <fork-version> [<target>]
#        fork-dependencies.sh repair <fork-version> <upstream-version>
#
# The fork builds a few Compose modules and publishes them to ~/.m2 under a version of its own
# (EXTENDED_AS in build-compose.sh). Gradle writes every dependency on a sibling module at that
# same version, built or not, so foundation 1.11.1001 asks for runtime 1.11.1001, which does
# not exist anywhere. The Kotlin toolchain then goes to the network for it and fails with
# "Unable to download checksums of file ...", and records that failure in its own cache.
#
# A dependency at the fork's version is kept when the module it names is in ~/.m2 at that
# version for every target the depending publication serves. For a target's own publication
# (foundation-macosarm64) that is the dependency's root and its publication for the same target.
# For a root (foundation) it is the dependency's root and its publication for every target the
# root's module was published for here, because the root is one file every target reads. A
# module whose desktop build was published and whose macOS build was not is therefore not
# published as far as a macOS consumer is concerned.
#
#   check   prints each dependency that fails that test and exits 1 if there is any. With a
#           target (macosarm64, desktop), only roots and that target's publications are read.
#   repair  points each dependency that fails it back at the upstream version, in place. It
#           reads every .pom and .module under the fork's version, not only what the last build
#           wrote, so it repairs what an earlier run left behind and is safe to run again.
#
# Only groups under org.jetbrains.compose and org.jetbrains.skiko are read or changed.
set -euo pipefail

usage() { echo "usage: fork-dependencies.sh check <fork-version> [<target>] | repair <fork-version> <upstream-version>" >&2; exit 2; }

mode="${1:-}"
version="${2:-}"
[[ -n "$version" ]] || usage
case "$mode" in
    check)  argument="${3:-}" ;;
    repair) argument="${3:-}"; [[ -n "$argument" ]] || usage ;;
    *) usage ;;
esac

repository="$HOME/.m2/repository"
[[ -d "$repository/org/jetbrains" ]] || exit 0

MODE="$mode" VERSION="$version" ARGUMENT="$argument" REPOSITORY="$repository" perl -e '
use strict;
use warnings;
use File::Find;

my ($mode, $version, $argument, $repository) = @ENV{qw(MODE VERSION ARGUMENT REPOSITORY)};
my @targets = qw(desktop jvm android macosarm64 macosx64 linuxx64 linuxarm64 mingwx64
                 iosarm64 iosx64 iossimulatorarm64 wasmjs js);
my $target_pattern = join "|", @targets;

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
my $root = "$repository/org/jetbrains";
find({ no_chdir => 1, wanted => sub {
    return unless -f $_ && m{/\Q$version\E/[^/]+-\Q$version\E\.(?:pom|module)$};
    push @files, $_;
} }, $root);

my @problems;
for my $file (sort @files) {
    my ($group_path, $artifact) = $file =~ m{^\Q$repository\E/(.+)/([^/]+)/\Q$version\E/[^/]+$} or next;
    (my $group = $group_path) =~ s{/}{.}g;
    next unless $group =~ /^org\.jetbrains\.(?:compose|skiko)/;
    my ($base, $own_target) = split_target($artifact);
    next if $mode eq "check" && $argument ne "" && defined $own_target && $own_target ne $argument;
    my @served = served_targets($group, $artifact);

    open my $in, "<", $file or die "cannot read $file: $!\n";
    local $/;
    my $text = <$in>;
    close $in;
    my $before = $text;

    my $decide = sub {
        my ($dependency_group, $dependency) = @_;
        return 1 unless $dependency_group =~ /^org\.jetbrains\.(?:compose|skiko)/;
        return 1 if is_published($dependency_group, $dependency, \@served);
        my $relative = $file;
        $relative =~ s{^\Q$repository\E/}{};
        push @problems, "$dependency_group:$dependency:$version, named by $relative";
        return 0;
    };

    # Gradle module metadata: dependencies and constraints, every version field at the fork version.
    $text =~ s{(\{\s*"group":\s*"([^"]+)",\s*"module":\s*"([^"]+)",\s*"version":\s*\{)([^{}]*)(\})}{
        my ($head, $dependency_group, $dependency, $fields, $tail) = ($1, $2, $3, $4, $5);
        if ($fields =~ /"\Q$version\E"/ && !$decide->($dependency_group, $dependency)) {
            $fields =~ s{("[a-z]+":\s*")\Q$version\E(")}{$1$argument$2}g if $mode eq "repair";
        }
        $head . $fields . $tail
    }ge;

    # Maven: every <dependency> at the fork version, in dependencies and dependencyManagement.
    $text =~ s{(<dependency>)(.*?)(</dependency>)}{
        my ($open, $body, $close) = ($1, $2, $3);
        if ($body =~ m{<groupId>\s*([^<\s]+)\s*</groupId>} ) {
            my $dependency_group = $1;
            my ($dependency) = $body =~ m{<artifactId>\s*([^<\s]+)\s*</artifactId>};
            if (defined $dependency && $body =~ m{<version>\s*\Q$version\E\s*</version>}
                && !$decide->($dependency_group, $dependency)) {
                $body =~ s{(<version>\s*)\Q$version\E(\s*</version>)}{$1$argument$2} if $mode eq "repair";
            }
        }
        $open . $body . $close
    }gse;

    if ($mode eq "repair" && $text ne $before) {
        open my $out, ">", $file or die "cannot write $file: $!\n";
        print $out $text;
        close $out;
        # Gradle and Maven check these when they are there, and a rewritten file no longer matches.
        unlink map { "$file.$_" } qw(md5 sha1 sha256 sha512);
        print "repointed $file\n";
    }
}

if ($mode eq "check" && @problems) {
    my %seen;
    print "$_\n" for grep { !$seen{$_}++ } @problems;
    exit 1;
}
'
