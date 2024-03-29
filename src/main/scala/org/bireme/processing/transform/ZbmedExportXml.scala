package org.bireme.processing.transform

import com.google.gson.Gson
import org.bireme.processing.`export`.ZbmedToXml
import org.bireme.processing.tools.models.{ZbmedXmlParameters, ZbmedDoc}
import org.bireme.processing.tools.mrw.{MongoDbReader, MongoDbWriter, mdrParameters, mdwParameters}
import org.bson.Document
import org.slf4j.{Logger, LoggerFactory}

import java.util.Date
import scala.collection.mutable.ListBuffer
import scala.util.{Success, Try}

class ZbmedExportXml {

  val logger: Logger = LoggerFactory.getLogger(classOf[ZbmedExportXml])

  def exportXml(parameters: ZbmedXmlParameters): Try[Unit] = {
    Try {
      System.out.println("\n")
      logger.info(s"Normalization started - ZBMed preprints ${new Date()}")

      val paramsRead: mdrParameters = mdrParameters(parameters.databaseRead, parameters.collectionRead,
        parameters.hostRead, parameters.portRead, parameters.userRead, parameters.passwordRead)
      val mExportRead: MongoDbReader = new MongoDbReader(paramsRead)

      val databaseWrite: String = parameters.databaseWrite.getOrElse(parameters.databaseRead)
      val collectionWrite: String = parameters.collectionWrite.getOrElse(parameters.collectionRead.concat("Normalized"))
      val hostWrite: Option[String] = parameters.hostWrite orElse parameters.hostRead
      val portWrite: Option[Int] = parameters.portWrite orElse parameters.portRead
      val userWrite: Option[String] = parameters.userWrite orElse parameters.userRead
      val passwordWrite: Option[String] = parameters.passwordWrite orElse parameters.passwordRead

      val paramsWrite: mdwParameters = mdwParameters(databaseWrite, collectionWrite, clear = true, addUpdDate = true, idField = None,
        hostWrite, portWrite, userWrite, passwordWrite, parameters.indexName)
      val mExportWrite: MongoDbWriter = new MongoDbWriter(paramsWrite)

      val docsMongo: Seq[Map[String, AnyRef]] = mExportRead.iterator().get.toSeq

      if (existDocumentsOrStop(docsMongo, parameters)) {
        processData(docsMongo, parameters, paramsWrite, mExportRead, mExportWrite)
      }
    }
  }

  private def processData(docsMongo: Seq[Map[String, AnyRef]], parameters: ZbmedXmlParameters, paramsWrite: mdwParameters, mExportRead: MongoDbReader, mExportWrite: MongoDbWriter): Try[Unit] = {
    Try {
      val buffer: ListBuffer[ZbmedDoc] = new ListBuffer[ZbmedDoc]
      val zbmedpp: ZbmedToXml = new ZbmedToXml
      val documentSeq: Seq[Document] = docsMongo.map(mExportWrite.convertToDocument1)

      zbmedpp.normalizeData(documentSeq, parameters.xmlOut) match {
        case Success(value) =>
          logger.info(s"Writing normalized documents in: database: ${parameters.databaseWrite.getOrElse(parameters.databaseRead)}," +
            s" collection: ${parameters.collectionWrite.getOrElse(parameters.collectionRead.concat("Normalized"))}," +
            s" host: ${parameters.hostWrite.getOrElse(parameters.hostRead.getOrElse("localhost"))}," +
            s" port: ${parameters.portWrite.getOrElse(parameters.portRead.getOrElse(27017))}," +
            s" user: ${parameters.userWrite.getOrElse(parameters.userRead.getOrElse("None"))}")

          var index = 0
          while (value.hasNext) {
            val zbmedpp_doc = value.next()
            index += 1
            zbmedpp.amountProcessed(docsMongo.length, index, if (docsMongo.length >= 10000) 10000 else docsMongo.length)
            buffer.append(zbmedpp_doc)
            if (buffer.length % 1000 == 0 || index == docsMongo.length) {
              insertDocumentNormalized(buffer, mExportWrite, paramsWrite.collection)
              buffer.clear()
            }
          }
          if (buffer.nonEmpty) {
            insertDocumentNormalized(buffer, mExportWrite, parameters.collectionWrite.get)
            buffer.clear()
          }
          if (mExportRead.countDocuments() != mExportWrite.countDocuments()) {
            logger.warn("---The quantity of documents differs between the collections")
          }
          mExportRead.close()
          mExportWrite.close()
          logger.info(s"FILE GENERATED SUCCESSFULLY IN: ${parameters.xmlOut}")
      }
    }
  }

  def insertDocumentNormalized(docs: ListBuffer[ZbmedDoc], mExport: MongoDbWriter, nameCollectionNormalized: String): Unit = {

    val docJson: ListBuffer[String] = docs.map(new Gson().toJson(_))

    if (!mExport.existCollection(nameCollectionNormalized)) {
      mExport.createCollection(nameCollectionNormalized)
    }
    mExport.insertDocumentNormalized(docJson.toList)
  }

  def existDocumentsOrStop(docsMongo: Seq[Map[String, AnyRef]], parameters: ZbmedXmlParameters): Boolean = {
    docsMongo.length match {
      case docs if docs == 0 =>
        throw new Exception(s"${logger.warn("No documents found check collection and parameters")}")
        false
      case docs if docs > 0 =>
        logger.info(s"Connected to mongodb - database: ${parameters.databaseRead}, collection: ${parameters.collectionRead}," +
          s" host: ${parameters.hostRead.getOrElse("localhost")}, port: ${parameters.portRead.getOrElse(27017)}, user: ${parameters.userRead.getOrElse("None")}")
        logger.info(s"Total documents: ${docsMongo.length}")
        true
    }
  }
}