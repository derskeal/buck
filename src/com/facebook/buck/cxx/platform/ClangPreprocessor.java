/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx.platform;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.MoreIterables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;

public class ClangPreprocessor extends AbstractPreprocessor {

  public ClangPreprocessor(Tool tool) {
    super(tool);
  }

  @Override
  public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
    return Optional.of(ImmutableList.of("-fcolor-diagnostics"));
  }

  @Override
  public boolean supportsHeaderMaps() {
    return true;
  }

  @Override
  public boolean supportsPrecompiledHeaders() {
    return true;
  }

  @Override
  public final Iterable<String> localIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(Iterables.cycle("-I"), includeRoots);
  }

  @Override
  public final Iterable<String> systemIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(Iterables.cycle("-isystem"), includeRoots);
  }

  @Override
  public final Iterable<String> quoteIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(Iterables.cycle("-iquote"), includeRoots);
  }

  @Override
  public final Iterable<String> prefixHeaderArgs(
      SourcePathResolver resolver, SourcePath prefixHeader) {
    return ImmutableList.of("-include", resolver.getAbsolutePath(prefixHeader).toString());
  }

  @Override
  public Iterable<String> precompiledHeaderArgs(Path pchOutputPath) {
    return ImmutableList.of(
        "-include-pch",
        pchOutputPath.toString(),
        // Force clang to accept pch even if mtime of its input changes, since buck tracks
        // input contents, this should be safe.
        "-Wp,-fno-validate-pch");
  }
}
