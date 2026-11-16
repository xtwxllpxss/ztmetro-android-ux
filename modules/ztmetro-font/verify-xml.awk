# verify-xml.awk — strict-enough well-formedness check for Android font XML.
#
# Deliberately small and POSIX awk (toybox/mawk/busybox all run it). It checks:
#   * every start tag is closed in the right order, self-closing tags are handled;
#   * comments, the <?xml?> declaration and <!DOCTYPE> are skipped;
#   * exactly one root element exists and it is closed.
# It does not resolve entities or validate against a DTD; it exists so a font config
# splice cannot install a truncated or unbalanced file.
#
# Usage: awk -f verify-xml.awk FILE
# Exit: 0 well-formed, 1 malformed.
BEGIN {
  pending = ""
  depth = 0
  in_comment = 0
  root = 0
  bad = 0
}

function report(msg) {
  if (!bad) {
    printf("verify-xml: %s (near line %d)\n", msg, NR) > "/dev/stderr"
  }
  bad = 1
}

{
  line = $0
  if (pending != "") {
    # Continue a start tag that spanned lines (AOSP fonts.xml has these).
    line = pending line
    pending = ""
  }
  pos = 1
  len = length(line)

  while (pos <= len) {
    if (in_comment) {
      rest = substr(line, pos)
      p = index(rest, "-->")
      if (p == 0) {
        pos = len + 1
        break
      }
      pos = pos + p + 2
      in_comment = 0
      continue
    }

    rest = substr(line, pos)
    lt = index(rest, "<")
    if (lt == 0) {
      break
    }
    pos = pos + lt - 1

    # Comment opening: handle before looking for ">", because "<!--" and the
    # first lines of a multi-line comment have no ">" on them.
    if (substr(line, pos, 4) == "<!--") {
      rest = substr(line, pos + 4)
      p = index(rest, "-->")
      if (p == 0) {
        in_comment = 1
        pos = len + 1
        break
      }
      pos = pos + 4 + p + 2
      continue
    }

    rest = substr(line, pos)
    gt = index(rest, ">")
    if (gt == 0) {
      pending = rest
      pos = len + 1
      break
    }
    tag = substr(line, pos + 1, gt - 2)
    pos = pos + gt

    # Comment: <!-- ... -->, possibly ending on a later line.
    if (substr(tag, 1, 3) == "!--") {
      if (tag == "!--" || substr(tag, length(tag) - 1) != "--") {
        in_comment = 1
      }
      continue
    }

    # Declaration or doctype.
    if (substr(tag, 1, 1) == "?" || substr(tag, 1, 1) == "!") {
      continue
    }

    if (substr(tag, 1, 1) == "/") {
      name = substr(tag, 2)
      sub(/[[:space:]].*/, "", name)
      if (depth == 0) {
        report("closing tag without opening: </" name ">")
        continue
      }
      if (stack[depth] != name) {
        report("mismatched tag: expected </" stack[depth] ">, got </" name ">")
      }
      depth--
      continue
    }

    selfclose = 0
    if (substr(tag, length(tag), 1) == "/") {
      selfclose = 1
      tag = substr(tag, 1, length(tag) - 1)
    }
    name = tag
    sub(/[[:space:]].*/, "", name)
    if (name !~ /^[A-Za-z_][A-Za-z0-9_.:-]*$/) {
      report("invalid tag name [" name "]")
      continue
    }
    if (depth == 0) {
      root++
    }
    if (!selfclose) {
      depth++
      stack[depth] = name
    }
  }
}

END {
  if (pending != "") {
    report("unterminated tag at end of file")
  }
  if (in_comment) {
    report("unterminated comment")
  }
  if (depth != 0) {
    report("unclosed element <" stack[depth] ">")
  }
  if (root != 1) {
    report("expected exactly one root element, found " root)
  }
  exit bad ? 1 : 0
}
