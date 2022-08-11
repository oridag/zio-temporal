package zio.temporal.protobuf

import io.temporal.common.converter._
import scalapb.GeneratedFileObject
import zio.temporal.experimentalApi
import zio.temporal.protobuf.internal.ProtoFileObjectAutoLoader

object ProtobufDataConverter {

  /** Creates data converted supporting given protobuf generated types
    * @param files
    *   generated protobuf files
    * @return
    *   a [[DataConverter]] supporting given protobuf types
    */
  def make(files: Seq[GeneratedFileObject]): DataConverter =
    new DefaultDataConverter(
      // order matters!
      Seq(
        new NullPayloadConverter(),
        new ByteArrayPayloadConverter(),
        new ProtobufJsonPayloadConverter(),
        new ScalapbPayloadConverter(files),
        new JacksonJsonPayloadConverter() // falling back to jackson for primitive types
      ): _*
    )

  /** Creates data converted supporting protobuf generated types. Loads all available protobuf descriptors generated by
    * protobuf.
    *
    * @param additionalFiles
    *   additional protobuf files to add
    * @return
    *   a [[DataConverter]] supporting given protobuf types
    */
  @experimentalApi
  def makeAutoLoad(additionalFiles: Seq[GeneratedFileObject] = Nil): DataConverter = {
    val autoLoaded = ProtoFileObjectAutoLoader.loadFromClassPath(getClass.getClassLoader)
    make(autoLoaded ++ additionalFiles)
  }
}