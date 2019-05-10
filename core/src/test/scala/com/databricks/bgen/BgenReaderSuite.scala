package com.databricks.bgen

import java.io.{BufferedReader, InputStreamReader}

import scala.collection.JavaConverters._

import com.google.common.io.LittleEndianDataInputStream
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.types.StructType

import com.databricks.hls.sql.HLSBaseTest
import com.databricks.vcf.BgenRow

class BgenReaderSuite extends HLSBaseTest {

  private val testRoot = s"$testDataHome/bgen"

  private def iterateFile(path: String): Seq[BgenRow] = {
    val p = new Path(path)
    val fs = p.getFileSystem(spark.sparkContext.hadoopConfiguration)
    val baseStream = fs.open(p)
    val stream = new LittleEndianDataInputStream(baseStream)
    val header = new BgenHeaderReader(stream).readHeader()
    val iterator = new BgenFileIterator(header, stream, baseStream, 0, fs.getFileStatus(p).getLen)
    iterator.init()
    val ret = iterator.toList
    baseStream.close()
    ret
  }

  private def compareBgenToVcf(bgenPath: String, vcfPath: String): Unit = {
    val sess = spark
    import sess.implicits._

    val bgen = iterateFile(bgenPath)
      .sortBy(r => (r.contigName, r.start))
    val vcf = spark.read
      .format("com.databricks.vcf")
      .option("includeSampleIds", true)
      .option("vcfRowSchema", true)
      .load(vcfPath)
      .orderBy("contigName", "start")
      .as[BgenRow]
      .collect()
      .toSeq
      .map { r =>
        val names = r.names
        // QCTools incorrectly separates IDs with commas instead of semicolons when exporting to VCF
        r.copy(names = names.flatMap(_.split(",")))
      }

    assert(bgen.size == vcf.size)
    bgen.zip(vcf).foreach {
      case (r1, r2) =>
        assert(r1.copy(genotypes = null) == r2.copy(genotypes = null))
        r1.genotypes.zip(r2.genotypes).foreach {
          case (g1, g2) =>
            // Note: QCTools inserts a dummy "NA" sample ID if absent when exporting to VCF
            assert(g1.sampleId == g2.sampleId || g2.sampleId.get == "NA")
            g1.posteriorProbabilities.indices.foreach { i =>
              g1.posteriorProbabilities(i) == g2.posteriorProbabilities(i)
            }
        }
    }
  }

  private def getSampleIds(path: String, colIdx: Int = 1): Seq[String] = {
    val p = new Path(path)
    val fs = p.getFileSystem(spark.sparkContext.hadoopConfiguration)
    val stream = fs.open(p)
    val streamReader = new InputStreamReader(stream)

    val bufferedReader = new BufferedReader(streamReader)
    // The first two (2) lines in a .sample file are header lines
    val sampleIds = bufferedReader
      .lines()
      .skip(2)
      .iterator()
      .asScala
      .map(_.split(" ").apply(colIdx))
      .toList

    stream.close()
    sampleIds
  }

  test("unphased 8 bit") {
    compareBgenToVcf(s"$testRoot/example.8bits.bgen", s"$testRoot/example.8bits.vcf")
  }

  test("unphased 16 bit (with missing samples)") {
    compareBgenToVcf(s"$testRoot/example.16bits.bgen", s"$testRoot/example.16bits.vcf")
  }

  test("unphased 32 bit") {
    compareBgenToVcf(s"$testRoot/example.32bits.bgen", s"$testRoot/example.32bits.vcf")
  }

  test("phased") {
    compareBgenToVcf(s"$testRoot/phased.16bits.bgen", s"$testRoot/phased.16bits.vcf")
  }

  test("complex 16 bit") {
    compareBgenToVcf(s"$testRoot/complex.16bits.bgen", s"$testRoot/complex.16bits.vcf")
  }

  test("skip entire file") {
    val p = new Path(s"$testRoot/example.16bits.bgen")
    val fs = p.getFileSystem(spark.sparkContext.hadoopConfiguration)
    val baseStream = fs.open(p)
    val stream = new LittleEndianDataInputStream(baseStream)
    val header = new BgenHeaderReader(stream).readHeader()
    val iterator = new BgenFileIterator(
      header,
      stream,
      baseStream,
      fs.getFileStatus(p).getLen,
      fs.getFileStatus(p).getLen
    )
    iterator.init()
    assert(!iterator.hasNext) // should skip entire file
  }

  test("skip to last record") {
    val p = new Path(s"$testRoot/complex.16bits.bgen")
    val fs = p.getFileSystem(spark.sparkContext.hadoopConfiguration)
    val baseStream = fs.open(p)
    val stream = new LittleEndianDataInputStream(baseStream)
    val header = new BgenHeaderReader(stream).readHeader()
    val iterator = new BgenFileIterator(
      header,
      stream,
      baseStream,
      774L, // last record start position
      fs.getFileStatus(p).getLen
    )
    iterator.init()
    assert(iterator.toSeq.size == 1)
  }

  test("read with spark") {
    val sess = spark
    import sess.implicits._
    val path = s"$testRoot/example.16bits.bgen"
    val fromSpark = spark.read
      .format("com.databricks.bgen")
      .load(path)
      .orderBy("contigName", "start")
      .as[BgenRow]
      .collect()
      .toSeq
    val direct = iterateFile(path).sortBy(r => (r.contigName, r.start))

    assert(fromSpark.size == direct.size)
    assert(fromSpark == direct)
  }

  test("read with spark (no index)") {
    val path = s"$testRoot/example.16bits.noindex.bgen"
    val fromSpark = spark.read
      .format("com.databricks.bgen")
      .load(path)
      .orderBy("contigName", "start")
      .collect()

    assert(fromSpark.length == iterateFile(path).size)
  }

  test("read only certain fields") {
    val sess = spark
    import sess.implicits._

    val path = s"$testRoot/example.16bits.bgen"
    val allele = spark.read
      .format("com.databricks.bgen")
      .load(path)
      .orderBy("start")
      .select("referenceAllele")
      .as[String]
      .first

    assert(allele == "A")
  }

  case class WeirdSchema(animal: String)
  test("be permissive if schema includes fields that can't be derived from VCF") {
    val path = s"$testRoot/example.16bits.noindex.bgen"
    spark.read
      .schema(ScalaReflection.schemaFor[WeirdSchema].dataType.asInstanceOf[StructType])
      .format("com.databricks.bgen")
      .load(path)
      .show() // No error expected
  }

  test("No sample IDs if no .sample file is provided") {
    val sess = spark
    import sess.implicits._

    val basePath = s"$testRoot/example.16bits.oxford"
    val ds = spark.read
      .format("com.databricks.bgen")
      .load(s"$basePath.bgen")
      .as[BgenRow]
      .head

    assert(ds.genotypes.forall(_.sampleId.isEmpty))
  }

  test("Sample IDs present if .sample file is provided") {
    val sess = spark
    import sess.implicits._

    val basePath = s"$testRoot/example.16bits.oxford"
    val ds = spark.read
      .option("sampleFilePath", s"$basePath.sample")
      .option("sampleIdColumn", "ID_2")
      .format("com.databricks.bgen")
      .load(s"$basePath.bgen")
      .as[BgenRow]
      .head

    val sampleIds = getSampleIds(s"$basePath.sample")
    assert(ds.genotypes.map(_.sampleId.get) == sampleIds)
  }

  test("Sample IDs present if no sample column provided but matches default") {
    val sess = spark
    import sess.implicits._

    val basePath = s"$testRoot/example.16bits.oxford"
    val ds = spark.read
      .option("sampleFilePath", s"$basePath.sample")
      .format("com.databricks.bgen")
      .load(s"$basePath.bgen")
      .as[BgenRow]
      .head

    val sampleIds = getSampleIds(s"$basePath.sample")
    assert(ds.genotypes.map(_.sampleId.get) == sampleIds)
  }

  test("Returns all sample IDs provided in corrupted .sample file") {
    val sess = spark
    import sess.implicits._

    val basePath = s"$testRoot/example.16bits.oxford"
    assertThrows[IllegalArgumentException](
      spark.read
        .option("sampleFilePath", s"$basePath.corrupted.sample")
        .format("com.databricks.bgen")
        .load(s"$basePath.bgen")
        .as[BgenRow]
        .head
    )
  }

  test("Only uses .sample file if no samples in bgen file") {
    val sess = spark
    import sess.implicits._

    val path = s"$testRoot/example.16bits.bgen"
    val ds = spark.read
      .option("sampleFilePath", s"$testRoot/example.fake.sample")
      .format("com.databricks.bgen")
      .load(path)
      .as[BgenRow]
      .head

    assert(ds.genotypes.forall(!_.sampleId.get.startsWith("fake")))
  }

  test("Throws if wrong provided column name") {
    val sess = spark
    import sess.implicits._

    val basePath = s"$testRoot/example.16bits.oxford"
    assertThrows[IllegalArgumentException](
      spark.read
        .option("sampleFilePath", s"$basePath.sample")
        .option("sampleIdColumn", "FAKE")
        .format("com.databricks.bgen")
        .load(s"$basePath.bgen")
        .as[BgenRow]
        .head
    )
  }

  test("Sample IDs present using provided column name") {
    val sess = spark
    import sess.implicits._

    val ds = spark.read
      .option("sampleFilePath", s"$testRoot/example.sample")
      .option("sampleIdColumn", "ID_1")
      .format("com.databricks.bgen")
      .load(s"$testRoot/example.16bits.oxford.bgen")
      .as[BgenRow]
      .head

    val sampleIds = getSampleIds(s"$testRoot/example.sample", 0)
    assert(ds.genotypes.map(_.sampleId.get) == sampleIds)
  }

  test("Throws if default sample column doesn't match") {
    val sess = spark
    import sess.implicits._

    assertThrows[IllegalArgumentException](
      spark.read
        .option("sampleFilePath", s"$testRoot/example.sample")
        .format("com.databricks.bgen")
        .load(s"$testRoot/example.16bits.oxford.bgen")
        .as[BgenRow]
        .head
    )
  }
}