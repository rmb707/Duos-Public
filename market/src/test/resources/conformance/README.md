# Parser test inputs

These two files are inputs for `SchemaConformanceTest` and the fuzz targets, not published packages: they fill in every
field and every block type, including ones no real package uses yet, so the schemas and the parsers can be compared on
all of them. Real content lives in `docs/sdk/source/`, and the tests read that too.
