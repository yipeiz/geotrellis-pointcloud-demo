package com.azavea.pointcloud.ingest

import com.azavea.pointcloud.ingest.conf.IngestConf

import geotrellis.pointcloud.pipeline._
import geotrellis.pointcloud.spark._
import geotrellis.pointcloud.spark.dem.{PointCloudToDem, PointToGrid}
import geotrellis.pointcloud.spark.io.hadoop._
import geotrellis.pointcloud.spark.tiling.CutPointCloud
import geotrellis.proj4.CRS
import geotrellis.raster.io._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.raster._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.io.index.ZCurveKeyIndexMethod
import geotrellis.spark.io.kryo.KryoRegistrator
import geotrellis.spark.pyramid.Pyramid
import geotrellis.spark.tiling._
import geotrellis.util._
import geotrellis.vector._

import org.apache.hadoop.fs.Path
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.{SparkConf, SparkContext}

object IngestIDWPyramid {
  def main(args: Array[String]): Unit = {
    val opts      = IngestConf.parse(args)
    // val chunkPath = System.getProperty("user.dir") + "/chunks/"

    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName("PointCloudCount")
      .set("spark.local.dir", "/data/spark")
      .set("spark.serializer", classOf[KryoSerializer].getName)
      .set("spark.kryo.registrator", classOf[KryoRegistrator].getName)

    implicit val sc = new SparkContext(conf)

    try {
      val options = HadoopPointCloudRDD.Options.DEFAULT.copy(
        pipeline =
          Read("", opts.inputCrs) ~
            ReprojectionFilter(opts.destCrs) ~
            RangeFilter(Some(s"Z[0:${opts.maxValue}]"))
      )

      val source = HadoopPointCloudRDD(new Path(opts.inputPath), options).cache()

      val (extent, crs) =
        source
          .map { case (header, _) => (header.projectedExtent3D.extent3d.toExtent, header.crs) }
          .reduce { case ((e1, c), (e2, _)) => (e1.combine(e2), c) }

      val targetCrs = CRS.fromName(opts.destCrs)

      val targetExtent =
        opts.extent match {
          case Some(e) => if (crs.epsgCode != targetCrs.epsgCode) e.reproject(crs, targetCrs) else e
          case _ =>  if (crs.epsgCode != targetCrs.epsgCode) extent.reproject(crs, targetCrs) else extent
        }

      val layoutScheme = if (opts.pyramid || opts.zoomed) ZoomedLayoutScheme(targetCrs) else FloatingLayoutScheme(512)

      val LayoutLevel(zoom, layout) = layoutScheme.levelFor(targetExtent, opts.cellSize)
      val kb = KeyBounds(layout.mapTransform(targetExtent))
      val md = TileLayerMetadata[SpatialKey](FloatConstantNoDataCellType, layout, targetExtent, targetCrs, kb)

      val tiled =
        CutPointCloud(
          source.flatMap(_._2),
          layout
        ).withContext {
          _.reduceByKey({ (p1, p2) => p1 union p2 }, opts.numPartitions)
        }

      val tiles =
        PointCloudToDem(
          tiled, opts.cellSize,
          PointToGrid.Options(cellType = FloatConstantNoDataCellType)
        )

      val layer = ContextRDD(tiles, md)

      layer.cache()

      def buildPyramid(zoom: Int, rdd: TileLayerRDD[SpatialKey])
                      (sink: (TileLayerRDD[SpatialKey], Int) => Unit): List[(Int, TileLayerRDD[SpatialKey])] = {
        if (zoom >= opts.minZoom) {
          rdd.cache()
          sink(rdd, zoom)
          val pyramidLevel @ (nextZoom, nextRdd) = Pyramid.up(rdd, layoutScheme, zoom)
          pyramidLevel :: buildPyramid(nextZoom, nextRdd)(sink)
        } else {
          sink(rdd, zoom)
          List((zoom, rdd))
        }
      }

      if(opts.persist) {
        val writer = HadoopLayerWriter(new Path(opts.catalogPath))
        val attributeStore = writer.attributeStore

        var savedHisto = false
        if (opts.pyramid) {
          buildPyramid(zoom, layer) { (rdd, zoom) =>
            writer
              .write[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](
              LayerId(opts.layerName, zoom),
              rdd,
              ZCurveKeyIndexMethod
            )

            println(s"=============================INGEST ZOOM LVL: $zoom=================================")

            if (!savedHisto) {
              savedHisto = true
              val histogram = rdd.histogram(512)
              attributeStore.write(
                LayerId(opts.layerName, 0),
                "histogram",
                histogram
              )
            }
          }.foreach { case (z, rdd) => rdd.unpersist(true) }
        } else {
          writer
            .write[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](
            LayerId(opts.layerName, 0),
            layer,
            ZCurveKeyIndexMethod
          )

          if (!savedHisto) {
            savedHisto = true
            val histogram = layer.histogram(512)
            attributeStore.write(
              LayerId(opts.layerName, 0),
              "histogram",
              histogram
            )
          }
        }
      }

      if(opts.testOutput.nonEmpty) {
        val raster = layer.stitch
        GeoTiff(raster, crs).write(opts.testOutput)
      } else if(!opts.persist) layer.count

      layer.unpersist(blocking = false)
      source.unpersist(blocking = false)

    } finally sc.stop()
  }
}
