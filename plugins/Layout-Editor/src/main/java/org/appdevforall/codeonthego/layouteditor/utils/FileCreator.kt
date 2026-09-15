package org.appdevforall.codeonthego.layouteditor.utils

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/**
 * FileCreator Class is used to create a file with given name and MIME Type.
 */
abstract class FileCreator(fragment: Fragment) {
  /** To create a file  */
  private val createFile: ActivityResultLauncher<String>

  /** MIME Type of file  */
  private var mimeType = "*/*"

  init {
    this.createFile =
      fragment.registerForActivityResult<String, Uri>(
        ActivityResultContracts.CreateDocument(mimeType)
      ) { onCreateFile(it) }
  }

  /**
   * Abstract method onCreateFile to call on result
   */
  abstract fun onCreateFile(uri: Uri)

  /**
   * Method to create file
   *
   * @param fileName The name of the file
   * @param mimeType The MIME type of the file
   */
  fun create(fileName: String, mimeType: String) {
    this.mimeType = mimeType // Set MIME type
    createFile.launch(fileName) // Launch file
  }
}
