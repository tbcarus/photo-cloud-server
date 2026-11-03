package ru.tbcarus.photocloudserver.exception;

// Операция затрагивает дерево активного автоматического источника и не принадлежит его механизму резервирования.
public class ManagedFolderException extends RuntimeException {

    public ManagedFolderException(String message) {
        super(message);
    }
}
