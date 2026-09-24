# Compatibility corpus, format v1

Real packages, packed the way `folio-pkg` will pack them. **Every one of these has to keep installing in every future
version of Folio**: that is the promise the format makes to anyone who publishes a package.

`CompatibilityCorpusTest` installs each one, checks what it changed, and removes it again. Add a package here whenever
the format grows something worth keeping honest; never change one that's already here, because then it isn't a record
of what v1 meant.
