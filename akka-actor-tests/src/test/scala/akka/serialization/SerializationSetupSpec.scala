/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.serialization

import akka.actor.setup.ActorSystemSetup
import akka.actor.{ ActorSystem, BootstrapSetup, ExtendedActorSystem, Terminated }
import akka.testkit.{ AkkaSpec, TestKit, TestProbe }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

class ConfigurationDummy
class ProgrammaticDummy
case class ProgrammaticJavaDummy()
case class SerializableDummy() // since case classes are serializable

object SerializationSetupSpec {

  val testSerializer = new TestSerializer

  val serializationSettings = SerializationSetup { _ ⇒
    List(
      SerializerDetails("test", testSerializer, List(classOf[ProgrammaticDummy])))
  }
  val bootstrapSettings = BootstrapSetup(None, Some(ConfigFactory.parseString("""
    akka {
      actor {
        serialize-messages = off

        serialization-bindings {
          "akka.serialization.ConfigurationDummy" = test
        }
      }
    }
    """)), None)
  val actorSystemSettings = ActorSystemSetup(bootstrapSettings, serializationSettings)

  val noJavaSerializationSystem = ActorSystem("SerializationSettingsSpec" + "NoJavaSerialization", ConfigFactory.parseString(
    """
    akka {
      actor {
        allow-java-serialization = off
      }
    }
    """.stripMargin))
  val noJavaSerializer = new DisabledJavaSerializer(noJavaSerializationSystem.asInstanceOf[ExtendedActorSystem])

}

class SerializationSetupSpec extends AkkaSpec(
  ActorSystem("SerializationSettingsSpec", SerializationSetupSpec.actorSystemSettings)) {

  import SerializationSetupSpec._

  "The serialization settings" should {

    "allow for programmatic configuration of serializers" in {
      val serializer = SerializationExtension(system).findSerializerFor(new ProgrammaticDummy)
      serializer shouldBe theSameInstanceAs(testSerializer)
    }

    "allow a configured binding to hook up to a programmatic serializer" in {
      val serializer = SerializationExtension(system).findSerializerFor(new ConfigurationDummy)
      serializer shouldBe theSameInstanceAs(testSerializer)
    }

  }

  // This is a weird edge case, someone creating a JavaSerializer manually and using it in a system means
  // that they'd need a different actor system to be able to create it... someone MAY pick a system with
  // allow-java-serialization=on to create the SerializationSetup and use that SerializationSetup
  // in another system with allow-java-serialization=off
  val addedJavaSerializationSettings = SerializationSetup { _ ⇒
    List(
      SerializerDetails("test", testSerializer, List(classOf[ProgrammaticDummy])),
      SerializerDetails("java-manual", new JavaSerializer(system.asInstanceOf[ExtendedActorSystem]), List(classOf[ProgrammaticJavaDummy])))
  }
  val addedJavaSerializationProgramaticallyButDisabledSettings = BootstrapSetup(None, Some(ConfigFactory.parseString("""
    akka {
    loglevel = debug
      actor {
        allow-java-serialization = off
      }
    }
    """)), None)

  val addedJavaSerializationViaSettingsSystem =
    ActorSystem("addedJavaSerializationSystem", ActorSystemSetup(addedJavaSerializationProgramaticallyButDisabledSettings, addedJavaSerializationSettings))

  "Disabling java serialization" should {

    "throw if passed system to JavaSerializer has allow-java-serialization = off" in {
      intercept[DisabledJavaSerializer.JavaSerializationException] {
        new JavaSerializer(noJavaSerializationSystem.asInstanceOf[ExtendedActorSystem])
      }.getMessage should include("akka.actor.allow-java-serialization = off")

      intercept[DisabledJavaSerializer.JavaSerializationException] {
        SerializationExtension(addedJavaSerializationViaSettingsSystem).findSerializerFor(new ProgrammaticJavaDummy).toBinary(new ProgrammaticJavaDummy)
      }
    }

    "have replaced java serializer" in {
      val p = TestProbe()(addedJavaSerializationViaSettingsSystem) // only receiver has the serialization disabled

      p.ref ! new ProgrammaticJavaDummy
      SerializationExtension(system).findSerializerFor(new ProgrammaticJavaDummy).toBinary(new ProgrammaticJavaDummy)
      // should not receive this one, it would have been java serialization!
      p.expectNoMsg(100.millis)

      p.ref ! new ProgrammaticDummy
      p.expectMsgType[ProgrammaticDummy]
    }

    "disable java serialization also for incoming messages if serializer id usually would have found the serializer" in {
      val ser1 = SerializationExtension(system)
      val msg = SerializableDummy()
      val bytes = ser1.serialize(msg).get
      val serId = ser1.findSerializerFor(msg).identifier
      ser1.findSerializerFor(msg).includeManifest should ===(false)

      val ser2 = SerializationExtension(noJavaSerializationSystem)
      ser2.findSerializerFor(new SerializableDummy) should ===(noJavaSerializer)
      ser2.serializerByIdentity(serId) should ===(noJavaSerializer)
      intercept[DisabledJavaSerializer.JavaSerializationException] {
        ser2.deserialize(bytes, serId, "").get
      }
    }
  }

  override def afterTermination(): Unit = {
    TestKit.shutdownActorSystem(noJavaSerializationSystem)
    TestKit.shutdownActorSystem(addedJavaSerializationViaSettingsSystem)
  }

}
