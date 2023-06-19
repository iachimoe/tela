import {Editor} from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'

const createEditor = (element) => {
    return new Editor({
        element: element,
        autofocus: true,
        extensions: [
            StarterKit,
        ],
    })
}

const attachCommand = (editor, command) => {
    editor.chain().focus()[command]().run()
}

export {createEditor, attachCommand}
