package xyz.kd5ujc.ci

import cats.effect.IO

import weaver.SimpleIOSuite

import java.nio.file.{Files, Paths}
import scala.util.Try

/**
 * TDD tests for tessellation base image CI optimization feature.
 * These tests MUST FAIL before implementation to prove they test the right thing.
 * 
 * Specification: Pre-build tessellation JARs into Docker image to reduce E2E CI time.
 * Expected improvement: ~16 min → ~8-10 min (40-45% improvement)
 */
object TessellationBaseImageTest extends SimpleIOSuite {

  test("build-tessellation-base.yml workflow should exist") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      expect(Files.exists(workflowPath))
    }
  }

  test("build-tessellation-base.yml should trigger on manual dispatch") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      expect(content.contains("workflow_dispatch")) &&
      expect(content.contains("on:"))
    }
  }

  test("build-tessellation-base.yml should trigger on tessellation version changes") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      // Should trigger when tessellation version in project files changes
      expect(content.contains("push:")) &&
      expect(content.contains("paths:")) &&
      (expect(content.contains("project/")) || expect(content.contains("build.sbt")))
    }
  }

  test("build-tessellation-base.yml should publish to GHCR") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      expect(content.contains("ghcr.io/ottobot-ai/tessellation-base")) &&
      expect(content.contains("docker push"))
    }
  }

  test("tessellation-base.Dockerfile should exist") {
    IO {
      val dockerfilePath = Paths.get("tessellation-base.Dockerfile")
      expect(Files.exists(dockerfilePath))
    }
  }

  test("tessellation-base.Dockerfile should use minimal base image") {
    IO {
      val dockerfilePath = Paths.get("tessellation-base.Dockerfile")
      val content = Files.readString(dockerfilePath)
      
      // Should use scratch or minimal base for JAR-only image
      expect(content.contains("FROM scratch") || content.contains("FROM alpine")) &&
      expect(content.contains("COPY")) &&
      expect(content.contains(".jar"))
    }
  }

  test("tessellation-base.Dockerfile should copy tessellation JARs") {
    IO {
      val dockerfilePath = Paths.get("tessellation-base.Dockerfile")
      val content = Files.readString(dockerfilePath)
      
      // Should copy gl0, gl1, keytool, wallet JARs but NOT metagraph JARs
      expect(content.contains("gl0") || content.contains("keytool")) &&
      expect(content.contains(".jar")) &&
      expect(!content.contains("ml0")) && // metagraph JARs should not be pre-built
      expect(!content.contains("dl1"))
    }
  }

  test("e2e.yml should be updated to extract JARs from pre-built image") {
    IO {
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Should use docker create + docker cp pattern to extract JARs
      expect(content.contains("docker create")) &&
      expect(content.contains("docker cp")) &&
      expect(content.contains("tessellation-base"))
    }
  }

  test("e2e.yml should set SKIP_ASSEMBLY=true") {
    IO {
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Should skip tessellation assembly since JARs are pre-built
      expect(content.contains("SKIP_ASSEMBLY=true"))
    }
  }

  test("e2e.yml should set PUBLISH=false") {
    IO {
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Should skip publishing since we're using pre-built JARs
      expect(content.contains("PUBLISH=false"))
    }
  }

  test("e2e.yml should still build metagraph from source") {
    IO {
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Metagraph JARs (ml0, cl1, dl1) should still be built from source
      expect(content.contains("--metagraph")) &&
      expect(content.contains("--dl1")) &&
      expect(!content.contains("SKIP_METAGRAPH_ASSEMBLY=true"))
    }
  }

  test("CI time improvement should be measurable") {
    IO {
      // This test will measure actual CI performance after implementation
      // For now, it should fail since no optimization exists yet
      val ciTimeBenchmark = measureE2ECITime()
      
      // Current baseline is ~16 minutes, target is ≤10 minutes
      expect(ciTimeBenchmark <= 10.0) // This should FAIL before implementation
    }
  }

  test("tessellation JARs should be extractable from pre-built image") {
    IO {
      // Test that we can extract the expected JARs from the pre-built image
      val extractionTest = Try {
        // This would test the docker create + docker cp extraction pattern
        val imageExists = checkDockerImageExists("ghcr.io/ottobot-ai/tessellation-base:latest")
        val jarsExtractable = extractJarsFromImage("ghcr.io/ottobot-ai/tessellation-base:latest")
        imageExists && jarsExtractable
      }
      
      expect(extractionTest.isSuccess && extractionTest.get)
    }
  }

  test("pre-built image should be public and not require authentication") {
    IO {
      // Test that the image can be pulled without GITHUB_TOKEN
      val pullTest = Try {
        // This would test docker pull without auth
        pullDockerImagePublic("ghcr.io/ottobot-ai/tessellation-base:latest")
      }
      
      expect(pullTest.isSuccess && pullTest.get)
    }
  }

  test("workflow should tag image with version from build") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      // Should tag with version (e.g., v4.0.0-rc.2)
      expect(content.contains("$TESSELLATION_VERSION") || content.contains("tags:")) &&
      expect(content.contains("ghcr.io/ottobot-ai/tessellation-base"))
    }
  }

  // Helper methods that will be implemented with the actual functionality
  
  private def measureE2ECITime(): Double = {
    // This would measure actual CI time - for now return a high value to fail the test
    16.5 // Current baseline - should fail target of ≤10 minutes
  }

  private def checkDockerImageExists(imageName: String): Boolean = {
    // This would check if the Docker image exists in GHCR
    false // Should fail until image is published
  }

  private def extractJarsFromImage(imageName: String): Boolean = {
    // This would test the JAR extraction pattern
    false // Should fail until extraction logic is implemented
  }

  private def pullDockerImagePublic(imageName: String): Boolean = {
    // This would test pulling the image without authentication
    false // Should fail until image is public
    
    
  }
}