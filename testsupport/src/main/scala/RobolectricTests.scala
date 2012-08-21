package org.positronicnet.test

import org.scalatest._

import com.xtremelabs.robolectric._
import com.xtremelabs.robolectric.bytecode._
import com.xtremelabs.robolectric.res.ResourceLoader
import com.xtremelabs.robolectric.internal.RealObject;
import com.xtremelabs.robolectric.shadows.ShadowApplication;

import com.xtremelabs.robolectric.util.DatabaseConfig
import com.xtremelabs.robolectric.util.SQLiteMap

import android.net.Uri__FromAndroid     // from Robolectric shadows

import scala.collection.mutable.HashMap

// Run Scalatest suites with partial workalikes for Android framework
// classes defined, from the "Robolectric" library.  The imitations
// aren't perfect, but the process is better integrated with build
// tooling, and vastly faster, than trying to run tests in the
// emulator.  The hope is we come out ahead.

trait RobolectricTests
  extends org.scalatest.Suite
{
  // As with the "standard" robolectric test runner, for Junit 4,
  // Robolectric suites here exist in two copies: one loaded with the
  // usual classloader in the build environment, and a second, in
  // Robolectric's "shadow world", loaded by a class loader that 
  // augments the android.jar stub classes with Robolectric "shadow"
  // implementations.
  //
  // The build tool interacts with the "build world" version (which
  // it creates as usual); that one builds the "shadow world" copy,
  // and delegates to it to actually run tests.

  lazy val shadowSuite: RobolectricTests = {
    val shadowClass = RobolectricTests.classLoader.bootstrap( this.getClass )
    shadowClass.newInstance.asInstanceOf[ RobolectricTests ]
  }

  // Running a test.
  //
  // "Upper half":  set up the shadow wrangler, and tell the delegate
  // to actually run the test.

  protected abstract override def runTest(
    testName: String,
    reporter: Reporter,
    stopper: Stopper,
    configMap: Map[String, Any],
    tracker: Tracker
  ) = {
    RobolectricTests.classHandler.beforeTest
    try {
      shadowSuite.reallyRunTest( testName, reporter, stopper, 
                                 configMap, tracker )
    }
    finally {
      RobolectricTests.classHandler.afterTest
    }
  }

  // "Lower half":  invoked in the shadow environment.  Do final
  // initialization, and run the test.

  protected def reallyRunTest(
    testName: String,
    reporter: Reporter,
    stopper: Stopper,
    configMap: Map[String, Any],
    tracker: Tracker
  ) = {
    setupAndroidEnvironmentForTest( configMap )
    super.runTest( testName, reporter, stopper, configMap, tracker )
  }

  // Setting up the internal android environment for tests.
  // Only invoked from the "lower half", in the shadow environment.
  //
  // The "real" Robolectric test runner lets subclasses hook in here
  // to wire up more shadow classes, or recreate application state.

  def setupAndroidEnvironmentForTest( configMap: Map[String,Any] ) = {

    Robolectric.bindDefaultShadowClasses
    Robolectric.resetStaticState
    DatabaseConfig.setDatabaseMap( this.databaseMap )

    config( configMap ) match {
      case ( config, resourceLoader ) =>
        val app = new ApplicationResolver( config ).resolveApplication
        Robolectric.application = ShadowApplication.bind( app, resourceLoader )
    }
  }

  // Database Map.  Just doing the bare minimum for now... there's only one.

  lazy val databaseMap = new SQLiteMap

  // Managing configuration.  Robolectric needs to know where the
  // manifest and resources are; we use command-line arguments (or the
  // moral equivalent --- sbt testArgs or whatever) so the build tooling
  // can tell us.

  var configs = new HashMap[(String, String), 
                            (RobolectricConfig, ResourceLoader)]

  def config(configMap: Map[String,Any]):(RobolectricConfig,ResourceLoader) = {

    val manifestPath   = configMap( "androidManifestPath" ).asInstanceOf[String]
    val androidResPath = configMap( "androidResPath" ).asInstanceOf[String]
    val key            = (manifestPath, androidResPath)

    configs.get( key ) match {

      case Some( stuff ) => stuff

      case None =>

        if (!(new java.io.File( manifestPath )).exists)
          throw new java.io.IOException( manifestPath + " not found" )

        if (!(new java.io.File( androidResPath )).exists)
          throw new java.io.IOException( manifestPath + " not found" )

        val config = new RobolectricConfig( 
          new java.io.File( manifestPath ),
          new java.io.File( androidResPath ))

        val resourceLoader =
          new ResourceLoader( config.getSdkVersion,
                              Class.forName( config.getRClassName ),
                              config.getResourceDirectory,
                              config.getAssetsDirectory )

        configs( key ) = (config, resourceLoader)
        (config, resourceLoader)
    }
  }

}

// One-time setup for the Robolectric library.  

object RobolectricTests {

  lazy val classHandler = ShadowWrangler.getInstance

  lazy val classLoader = {

    val loader = new RobolectricClassLoader( classHandler )

    for (klass <- List( classOf[ Uri__FromAndroid  ],
                        classOf[ RealObject        ],
                        classOf[ ShadowWrangler    ],
                        classOf[ RobolectricConfig ],
                        classOf[ android.R         ]))
      loader.delegateLoadingOf( klass.getName )

    loader.delegateLoadingOf( "scala." )
    loader.delegateLoadingOf( "org.positronicnet.test.RobolectricTests" )
    loader.delegateLoadingOf( "org.scalatest." )
    loader.delegateLoadingOf( this.getClass.getName )
    loader
  }
}

