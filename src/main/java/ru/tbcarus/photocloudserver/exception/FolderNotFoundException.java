package ru.tbcarus.photocloudserver.exception;

public class FolderNotFoundException extends RuntimeException {

    public FolderNotFoundException(Long folderId) {
        super("Folder " + folderId + " not found");
    }

    public FolderNotFoundException(String message) {
        super(message);
    }
}
